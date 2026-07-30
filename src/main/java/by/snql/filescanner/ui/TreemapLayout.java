package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TreemapLayout {

    private TreemapLayout() {}

    public record Rect(double x, double y, double w, double h, FileNode node) {}

    public static Rect[] compute(FileNode root, double width, double height) {
        return layoutRect(root, 0, 0, width, height);
    }

    private static Rect[] layoutRect(FileNode node, double x, double y, double w, double h) {
        if (node == null || w <= 0 || h <= 0) return new Rect[0];

        int totalRects = 1;
        for (var child : node.getChildren()) {
            totalRects += countDescendants(child);
        }
        var rects = new Rect[totalRects];
        rects[0] = new Rect(x, y, w, h, node);

        if (node.isLeaf() || node.getChildren().isEmpty()) return rects;

        double padding = 3;
        double ix = x + padding;
        double iy = y + padding;
        double iw = w - padding * 2;
        double ih = h - padding * 2;
        if (iw <= 0 || ih <= 0) return rects;

        var map = squarify(node.getChildren(), ix, iy, iw, ih);
        int idx = 1;
        for (var child : node.getChildren()) {
            var r = map.get(child);
            var subRects = layoutRect(child, r.x, r.y, r.w, r.h);
            System.arraycopy(subRects, 0, rects, idx, subRects.length);
            idx += subRects.length;
        }
        return rects;
    }

    static Map<FileNode, Rect> squarify(List<FileNode> children,
                                         double x, double y, double w, double h) {
        var map = new LinkedHashMap<FileNode, Rect>();
        long total = children.stream().mapToLong(FileNode::getSize).sum();
        if (total == 0) {
            double perChild = w / Math.max(1, children.size());
            for (int i = 0; i < children.size(); i++) {
                map.put(children.get(i), new Rect(x + i * perChild, y, perChild, h, children.get(i)));
            }
            return map;
        }

        boolean vertical = w >= h;
        double pos = vertical ? x : y;
        double length = vertical ? w : h;
        double otherDim = vertical ? h : w;
        double otherPos = vertical ? y : x;

        for (var child : children) {
            double fraction = (double) child.getSize() / total;
            double size = length * fraction;
            size = Math.max(size, 4);
            if (vertical) {
                map.put(child, new Rect(pos, otherPos, size, otherDim, child));
            } else {
                map.put(child, new Rect(otherPos, pos, otherDim, size, child));
            }
            pos += size;
        }
        return map;
    }

    public static int countDescendants(FileNode node) {
        int count = 1;
        for (var child : node.getChildren()) {
            count += countDescendants(child);
        }
        return count;
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
}
