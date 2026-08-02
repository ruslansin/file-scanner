package by.snql.filescanner.ui;

import by.snql.filescanner.core.util.SizeFormat;
import by.snql.filescanner.model.FileNode;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.function.Consumer;

public class TreemapChart extends StackPane {

    private final Canvas canvas;
    private final Tooltip tooltip = new Tooltip();
    private FileNode root;
    private Consumer<FileNode> onNodeClicked;
    private TreemapLayout.Rect[] currentRects;
    private boolean redrawScheduled;

    public TreemapChart() {
        canvas = new Canvas();
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        getChildren().add(canvas);

        setMinSize(200, 200);

        tooltip.setShowDelay(Duration.millis(150));
        tooltip.setHideDelay(Duration.ZERO);
        Tooltip.install(canvas, tooltip);

        canvas.setOnMouseClicked(this::onMouseClicked);
        canvas.setOnMouseMoved(this::onMouseMoved);
        canvas.setOnMouseExited(e -> canvas.setCursor(javafx.scene.Cursor.DEFAULT));

        // Width and height typically change together on a resize; coalesce the two
        // notifications into a single layout+redraw pass instead of computing twice.
        widthProperty().addListener((obs, old, w) -> scheduleRedraw());
        heightProperty().addListener((obs, old, h) -> scheduleRedraw());
    }

    private void scheduleRedraw() {
        if (redrawScheduled) return;
        redrawScheduled = true;
        javafx.application.Platform.runLater(() -> {
            redrawScheduled = false;
            redraw();
        });
    }

    public void setRoot(FileNode node) {
        root = node;
        redraw();
    }

    public void setOnNodeClicked(Consumer<FileNode> handler) {
        onNodeClicked = handler;
    }

    public void clear() {
        root = null;
        currentRects = null;
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void redraw() {
        if (root == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        var gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        currentRects = TreemapLayout.compute(root, canvas.getWidth(), canvas.getHeight());
        draw(gc);
    }

    private void draw(GraphicsContext gc) {
        for (var r : currentRects) {
            if (r.node().getSize() == 0 && r.node().isDirectory()) continue;

            int depth = TreemapLayout.displayDepth(r.node());
            Color fill = colorFor(r.node(), depth);
            Color border = fill.darker();

            gc.setFill(fill);
            gc.fillRect(r.x(), r.y(), r.w(), r.h());

            gc.setStroke(border);
            gc.setLineWidth(0.5);
            gc.strokeRect(r.x(), r.y(), r.w(), r.h());

            drawLabel(gc, r);
            drawTruncatedMarker(gc, r);
        }
    }

    /**
     * Small corner marker for cells whose content was folded away by the render-depth cap
     * or by running out of pixels to subdivide legibly — so "there's more below, click to
     * drill down" is visible instead of the chart silently looking like a dead end (this is
     * how GNOME Baobab marks a ring segment whose children were cut off by its own depth
     * limit: a small arc at the segment's outer edge, rather than nothing at all).
     */
    private void drawTruncatedMarker(GraphicsContext gc, TreemapLayout.Rect r) {
        if (!r.truncated()) return;
        double size = Math.min(10, Math.min(r.w(), r.h()) * 0.4);
        if (size < 4) return;

        double x2 = r.x() + r.w();
        double y2 = r.y() + r.h();
        gc.setFill(Color.rgb(0, 0, 0, 0.45));
        gc.beginPath();
        gc.moveTo(x2, y2 - size);
        gc.lineTo(x2, y2);
        gc.lineTo(x2 - size, y2);
        gc.closePath();
        gc.fill();
    }

    /** Reused off-scene node purely for measuring glyph widths with the real font metrics
     *  instead of a crude "charCount * fontSize * constant" guess (which was inaccurate
     *  in both directions and caused labels to either overflow into neighboring
     *  rectangles or be needlessly hidden). */
    private static final Text MEASURER = new Text();

    private static double measureWidth(String text, Font font) {
        MEASURER.setText(text);
        MEASURER.setFont(font);
        return MEASURER.getLayoutBounds().getWidth();
    }

    private void drawLabel(GraphicsContext gc, TreemapLayout.Rect r) {
        if (r.w() < 24 || r.h() < 14) return;

        Font font = Font.font("SansSerif", 11);
        double textH = font.getSize();
        if (r.h() < textH + 4) return;

        double available = r.w() - 8;
        String name = r.node().getName();
        String size = SizeFormat.format(r.node().getSize());
        String text = name.isEmpty() ? size : name + "  " + size;

        if (measureWidth(text, font) > available) {
            text = size;
            if (measureWidth(text, font) > available) {
                return; // doesn't fit even as just the size — skip rather than draw a cut-off fragment
            }
        }

        // Clip to the rectangle so a slightly-off measurement never bleeds text into a
        // neighboring, later-drawn rectangle (which previously overpainted the tail of
        // the label, looking like the text was cut off mid-word).
        gc.save();
        gc.beginPath();
        gc.rect(r.x() + 1, r.y() + 1, r.w() - 2, r.h() - 2);
        gc.clip();
        gc.setFill(Color.WHITE);
        gc.setFont(font);
        gc.fillText(text, r.x() + 4, r.y() + textH + 2);
        gc.restore();
    }

    private Color colorFor(FileNode node, int depth) {
        if (node.isLeaf() || !node.isDirectory()) {
            return FileTypeCategory.colorFor(node.getName()).deriveColor(1, 1, 0.7 + depth * 0.05, 1);
        }
        double b = 0.5 + depth * 0.08;
        return Color.rgb((int)(52 * b), (int)(73 * b), (int)(94 * b));
    }

    /**
     * Finds the smallest-area (i.e. most specific/deepest) rectangle containing the given
     * point, optionally restricted to directories. The currently-displayed root's own
     * rectangle always spans the whole canvas, so a naive first-match-wins scan would
     * always pick it no matter where the point is — picking the smallest match instead
     * gives the deepest node actually under the cursor.
     */
    private TreemapLayout.Rect findSmallest(double x, double y, boolean directoriesOnly) {
        if (currentRects == null) return null;
        TreemapLayout.Rect best = null;
        for (var r : currentRects) {
            if (directoriesOnly && !r.node().isDirectory()) continue;
            if (x >= r.x() && x < r.x() + r.w() && y >= r.y() && y < r.y() + r.h()) {
                if (best == null || r.w() * r.h() < best.w() * best.h()) {
                    best = r;
                }
            }
        }
        return best;
    }

    private void onMouseClicked(MouseEvent e) {
        var best = findSmallest(e.getX(), e.getY(), true);
        if (best != null && best.node() != root && onNodeClicked != null) {
            onNodeClicked.accept(best.node());
        }
    }

    private void onMouseMoved(MouseEvent e) {
        if (currentRects == null) {
            canvas.setCursor(javafx.scene.Cursor.DEFAULT);
            return;
        }

        var hoveredDir = findSmallest(e.getX(), e.getY(), true);
        javafx.scene.Cursor cursor = hoveredDir != null ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT;
        if (canvas.getCursor() != cursor) {
            canvas.setCursor(cursor);
        }

        var hovered = findSmallest(e.getX(), e.getY(), false);
        if (hovered != null) {
            var node = hovered.node();
            String path = node.getPath() != null ? node.getPath().toString() : node.getName();
            tooltip.setText(path + "\n" + SizeFormat.format(node.getSize()));
        }
    }
}
