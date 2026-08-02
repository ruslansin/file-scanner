package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TreemapLayout {

    private TreemapLayout() {}

    /** Recursion safety limit — real filesystems are rarely nested this deep, but a
     *  pathological/adversarial tree (or a symlink-cycle placeholder chain) must not
     *  be able to blow the stack or make layout unboundedly expensive. */
    private static final int MAX_DEPTH = 200;

    public record Rect(double x, double y, double w, double h, FileNode node) {}

    public static Rect[] compute(FileNode root, double width, double height) {
        if (root == null || width <= 0 || height <= 0) return new Rect[0];
        var rects = new ArrayList<Rect>();
        layout(root, 0, 0, width, height, rects, 0);
        return rects.toArray(new Rect[0]);
    }

    private static void layout(FileNode node, double x, double y, double w, double h,
                                List<Rect> out, int depth) {
        double pad = 2;
        double ix = x + pad;
        double iy = y + pad;
        double iw = w - pad * 2;
        double ih = h - pad * 2;

        if (iw < 5 || ih < 5) return;

        out.add(new Rect(x, y, w, h, node));

        if (depth >= MAX_DEPTH || node.getChildren().isEmpty()) return;

        var dirs = new ArrayList<FileNode>();
        var files = new ArrayList<FileNode>();
        for (var c : node.getChildren()) {
            if (c.isDirectory()) dirs.add(c);
            else files.add(c);
        }

        var items = new ArrayList<FileNode>(dirs.size() + files.size());
        items.addAll(dirs);
        items.addAll(files);

        long totalSize = items.stream().mapToLong(FileNode::getSize).sum();
        if (totalSize == 0) {
            double perW = iw / items.size();
            for (int i = 0; i < items.size(); i++) {
                layout(items.get(i), ix + i * perW, iy, perW, ih, out, depth + 1);
            }
            return;
        }

        double minSize = totalSize * 0.001;
        var visible = items.stream()
                .filter(f -> f.getSize() >= minSize)
                .toList();

        if (visible.isEmpty()) {
            double perW = iw / items.size();
            for (int i = 0; i < items.size(); i++) {
                layout(items.get(i), ix + i * perW, iy, perW, ih, out, depth + 1);
            }
            return;
        }

        squarify(visible, ix, iy, iw, ih, out, depth + 1);
    }

    private static void squarify(List<FileNode> items, double x, double y, double w, double h,
                                  List<Rect> out, int depth) {
        if (items.isEmpty()) return;
        long total = items.stream().mapToLong(FileNode::getSize).sum();
        if (total == 0) return;

        double area = w * h;
        double scale = area / total;
        double totalFraction = 0;
        var row = new ArrayList<FileNode>();

        for (var child : items) {
            double fraction = child.getSize() * scale / area;
            row.add(child);
            totalFraction += fraction;

            double rowSize = totalFraction * (w >= h ? h : w);
            double worstAspect = worstAspectRatio(row, rowSize, w >= h ? w : h, total);

            if (row.size() > 1 && (worstAspect > worstAspectRatio(
                    row.subList(0, row.size() - 1),
                    (totalFraction - fraction) * (w >= h ? h : w),
                    w >= h ? w : h, total))) {
                row.remove(row.size() - 1);
                layoutRow(row, x, y, w, h, totalFraction - fraction, out, total, depth);
                double consumed = (totalFraction - fraction) * (w >= h ? w : h);
                if (w >= h) {
                    squarify(List.of(child), x + consumed, y, w - consumed, h, out, depth);
                } else {
                    squarify(List.of(child), x, y + consumed, w, h - consumed, out, depth);
                }
                return;
            }
        }
        layoutRow(row, x, y, w, h, totalFraction, out, total, depth);
    }

    private static void layoutRow(List<FileNode> row, double x, double y, double w, double h,
                                   double totalFraction, List<Rect> out, long total, int depth) {
        if (row.isEmpty()) return;
        double consumed = totalFraction * (w >= h ? h : w);
        long rowSize = row.stream().mapToLong(FileNode::getSize).sum();

        if (w >= h) {
            double ry = y;
            for (var child : row) {
                double frac = rowSize > 0 ? (double) child.getSize() / rowSize * consumed : consumed / row.size();
                double childH = Math.max(frac, 4);
                layout(child, x, ry, consumed, childH, out, depth);
                ry += childH;
            }
        } else {
            double rx = x;
            for (var child : row) {
                double frac = rowSize > 0 ? (double) child.getSize() / rowSize * consumed : consumed / row.size();
                double childW = Math.max(frac, 4);
                layout(child, rx, y, childW, consumed, out, depth);
                rx += childW;
            }
        }
    }

    private static double worstAspectRatio(List<FileNode> row, double side, double length, long total) {
        if (row.isEmpty()) return Double.MAX_VALUE;
        long rowSum = row.stream().mapToLong(FileNode::getSize).sum();
        double s = (double) rowSum / (total > 0 ? total : 1) * length;
        double aspectW = Math.max(side, s) / Math.min(side, s);
        double aspectH = Math.max(s, side) / Math.min(s, side);
        return Math.max(aspectW, aspectH);
    }

    public static int displayDepth(FileNode node) {
        int d = 0;
        var n = node;
        while (n.getChildren().size() == 1 && n.getChildren().get(0).isDirectory()) {
            d++;
            n = n.getChildren().get(0);
        }
        return Math.min(d, 4);
    }

    public static int countDescendants(FileNode node) {
        int count = 1;
        for (var child : node.getChildren()) {
            count += countDescendants(child);
        }
        return count;
    }

    static Map<FileNode, Rect> squarify(List<FileNode> children, double x, double y, double w, double h) {
        var dummy = new FileNode(null, "tmp", true, 1000);
        var sorted = new ArrayList<>(children);
        sorted.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
        for (var c : sorted) dummy.addChild(c);
        var out = new ArrayList<Rect>();
        layout(dummy, x, y, w, h, out, 0);
        var map = new LinkedHashMap<FileNode, Rect>();
        for (var r : out) {
            if (r.node() != dummy) map.putIfAbsent(r.node(), r);
        }
        return map;
    }
}
