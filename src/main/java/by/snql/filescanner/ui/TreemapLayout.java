package by.snql.filescanner.ui;

import by.snql.filescanner.config.Settings;
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

    /**
     * How many levels below the currently-displayed root get subdivided into their own
     * nested rectangles, GNOME Baobab-style: rather than recursing all the way down until
     * boxes are a handful of pixels wide (which used to bury the chart in thousands of tiny
     * slivers for anything with deep nesting, e.g. node_modules or Maven repositories),
     * deeper content is folded into a single flat-colored block sized by its aggregate
     * total. The user can still see and explore it by clicking to drill down — navigation
     * just becomes the way to see more detail, instead of everything being dumped into one
     * view at once. User-configurable via Settings ("Chart nesting depth").
     */
    private static int maxRenderDepth() {
        return Math.max(1, Settings.get().chartRenderDepth);
    }

    /**
     * {@code truncated} is true when this node has children that are NOT being drawn —
     * either because the render-depth cap was reached or because the box is too small to
     * subdivide legibly — so the UI can show a small "more content below, click to drill
     * down" indicator instead of silently hiding the fact that detail is being folded away
     * (GNOME Baobab marks this with a small arc at the edge of a truncated ring segment).
     */
    public record Rect(double x, double y, double w, double h, FileNode node, boolean truncated) {}

    public static Rect[] compute(FileNode root, double width, double height) {
        if (root == null || width <= 0 || height <= 0) return new Rect[0];
        var rects = new ArrayList<Rect>();
        layout(root, 0, 0, width, height, rects, 0);
        return rects.toArray(new Rect[0]);
    }

    private static void layout(FileNode node, double x, double y, double w, double h,
                                List<Rect> out, int depth) {
        if (w <= 0 || h <= 0) return;

        double pad = 2;
        double ix = x + pad;
        double iy = y + pad;
        double iw = w - pad * 2;
        double ih = h - pad * 2;

        boolean hasChildren = !node.getChildren().isEmpty();
        boolean tooSmallToSubdivide = iw < 5 || ih < 5;
        boolean depthCapReached = depth >= MAX_DEPTH || depth >= maxRenderDepth();
        boolean truncated = hasChildren && (tooSmallToSubdivide || depthCapReached);

        // Always draw the node's own rectangle first, even if it's too small to fit
        // padded children — otherwise the space allotted to it by the parent's row
        // layout is left unpainted, showing the raw canvas background as a gap.
        out.add(new Rect(x, y, w, h, node, truncated));

        if (tooSmallToSubdivide || depthCapReached || !hasChildren) return;

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
            layoutEqualShare(items, ix, iy, iw, ih, out, depth + 1);
            return;
        }

        // Only drop items that would be sub-pixel anyway (their share of the *actual*
        // pixel area available here rounds to nothing) — not a fixed 0.1%-of-total-size
        // cut regardless of how much screen space is actually available, which used to
        // hide plenty of items that would otherwise have been perfectly visible.
        double pixelArea = iw * ih;
        double minVisibleSize = pixelArea > 0 ? totalSize / pixelArea : 0;
        var visible = items.stream()
                .filter(f -> f.getSize() >= minVisibleSize)
                .toList();

        if (visible.isEmpty()) {
            layoutEqualShare(items, ix, iy, iw, ih, out, depth + 1);
            return;
        }

        squarify(visible, ix, iy, iw, ih, out, depth + 1);
    }

    private static void layoutEqualShare(List<FileNode> items, double x, double y, double w, double h,
                                          List<Rect> out, int depth) {
        if (items.isEmpty()) return;
        if (w >= h) {
            double perW = w / items.size();
            for (int i = 0; i < items.size(); i++) {
                layout(items.get(i), x + i * perW, y, perW, h, out, depth);
            }
        } else {
            double perH = h / items.size();
            for (int i = 0; i < items.size(); i++) {
                layout(items.get(i), x, y + i * perH, w, perH, out, depth);
            }
        }
    }

    /**
     * Standard squarified-treemap row building (Bruls/Huizing/van Wijk): grows a row by
     * adding items (in the given, pre-sorted order) along the rectangle's shorter side
     * until adding the next item would make the worst aspect ratio in the row worse, then
     * lays out that row and recurses into the *remaining* rectangle with *all* remaining
     * items — never dropping any of them.
     */
    private static void squarify(List<FileNode> items, double x, double y, double w, double h,
                                  List<Rect> out, int depth) {
        if (items.isEmpty() || w <= 0 || h <= 0) return;

        long remainingTotal = items.stream().mapToLong(FileNode::getSize).sum();
        if (remainingTotal <= 0) {
            layoutEqualShare(items, x, y, w, h, out, depth);
            return;
        }

        double area = w * h;
        double side = Math.min(w, h);

        var row = new ArrayList<FileNode>();
        long rowSum = 0;
        long rowMin = Long.MAX_VALUE;
        long rowMax = 0;
        int i = 0;
        for (; i < items.size(); i++) {
            long size = items.get(i).getSize();
            long newSum = rowSum + size;
            long newMin = Math.min(rowMin, size);
            long newMax = Math.max(rowMax, size);

            if (!row.isEmpty() &&
                    worst(newSum, newMin, newMax, side, area, remainingTotal) >
                    worst(rowSum, rowMin, rowMax, side, area, remainingTotal)) {
                break; // adding this item would make the row worse — finalize without it
            }
            row.add(items.get(i));
            rowSum = newSum;
            rowMin = newMin;
            rowMax = newMax;
        }

        boolean placeAlongX = w >= h;
        double thickness = (double) rowSum / remainingTotal * area / side;
        layoutRow(row, x, y, w, h, rowSum, thickness, placeAlongX, out, depth);

        var rest = items.subList(i, items.size());
        if (rest.isEmpty()) return;

        if (placeAlongX) {
            squarify(rest, x + thickness, y, w - thickness, h, out, depth);
        } else {
            squarify(rest, x, y + thickness, w, h - thickness, out, depth);
        }
    }

    private static void layoutRow(List<FileNode> row, double x, double y, double w, double h,
                                   long rowSum, double thickness, boolean placeAlongX,
                                   List<Rect> out, int depth) {
        if (row.isEmpty()) return;

        if (placeAlongX) {
            // A strip of width=thickness spanning the full height h; children stacked
            // vertically within it, each sized proportionally to their share of the row.
            double cy = y;
            for (var child : row) {
                double childH = rowSum > 0 ? (double) child.getSize() / rowSum * h : h / row.size();
                layout(child, x, cy, thickness, childH, out, depth);
                cy += childH;
            }
        } else {
            double cx = x;
            for (var child : row) {
                double childW = rowSum > 0 ? (double) child.getSize() / rowSum * w : w / row.size();
                layout(child, cx, y, childW, thickness, out, depth);
                cx += childW;
            }
        }
    }

    /**
     * Worst-case aspect ratio of a candidate row, using the classic formula from the
     * "Squarified Treemaps" paper: {@code max(side^2 * rMax / area^2, area^2 / (side^2 * rMin))}
     * where {@code area} is the row's total pixel area and {@code rMin}/{@code rMax} are the
     * pixel areas of its smallest/largest item.
     */
    private static double worst(long sizeSum, long minSize, long maxSize, double side,
                                 double totalArea, long remainingTotal) {
        if (sizeSum <= 0 || minSize <= 0 || remainingTotal <= 0) return Double.MAX_VALUE;
        double areaPerUnit = totalArea / remainingTotal;
        double rowArea = sizeSum * areaPerUnit;
        double rMax = maxSize * areaPerUnit;
        double rMin = minSize * areaPerUnit;
        double sideSq = side * side;
        double areaSq = rowArea * rowArea;
        return Math.max((sideSq * rMax) / areaSq, areaSq / (sideSq * rMin));
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
