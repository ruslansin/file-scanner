package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.util.function.Consumer;

public class TreemapChart extends StackPane {

    private final Canvas canvas;
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

        canvas.setOnMouseClicked(this::onMouseClicked);
        canvas.setOnMouseMoved(this::onMouseMoved);

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
        }
    }

    private void drawLabel(GraphicsContext gc, TreemapLayout.Rect r) {
        if (r.w() < 20 || r.h() < 14) return;

        String name = r.node().getName();
        String size = by.snql.filescanner.core.util.SizeFormat.format(r.node().getSize());
        String text = name + "  " + size;

        gc.setFill(Color.WHITE);
        gc.setFont(javafx.scene.text.Font.font("SansSerif", 11));

        if (r.w() < gc.getFont().getSize() * text.length() * 0.6) {
            text = size;
        }

        double textW = text.length() * gc.getFont().getSize() * 0.5;
        double textH = gc.getFont().getSize();

        if (r.w() > textW + 4 && r.h() > textH + 2) {
            gc.fillText(text, r.x() + 4, r.y() + textH + 2);
        }
    }

    private Color colorFor(FileNode node, int depth) {
        if (node.isLeaf() || !node.isDirectory()) {
            return FileTypeCategory.colorFor(node.getName()).deriveColor(1, 1, 0.7 + depth * 0.05, 1);
        }
        double b = 0.5 + depth * 0.08;
        return Color.rgb((int)(52 * b), (int)(73 * b), (int)(94 * b));
    }

    private void onMouseClicked(MouseEvent e) {
        if (currentRects == null) return;
        for (var r : currentRects) {
            if (e.getX() >= r.x() && e.getX() < r.x() + r.w() &&
                e.getY() >= r.y() && e.getY() < r.y() + r.h() &&
                r.node().isDirectory()) {
                if (onNodeClicked != null) {
                    onNodeClicked.accept(r.node());
                }
                return;
            }
        }
    }

    private void onMouseMoved(MouseEvent e) {
        if (currentRects == null) {
            canvas.setCursor(javafx.scene.Cursor.DEFAULT);
            return;
        }
        boolean overDir = false;
        for (var r : currentRects) {
            if (e.getX() >= r.x() && e.getX() < r.x() + r.w() &&
                e.getY() >= r.y() && e.getY() < r.y() + r.h() &&
                r.node().isDirectory()) {
                overDir = true;
                break;
            }
        }
        javafx.scene.Cursor cursor = overDir ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT;
        if (canvas.getCursor() != cursor) {
            canvas.setCursor(cursor);
        }
    }
}
