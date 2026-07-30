package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RingsChart extends StackPane {

    private final Canvas canvas;
    private FileNode root;
    private Consumer<FileNode> onNodeClicked;
    private Arc[] currentArcs;

    private static final double RING_WIDTH = 30;
    private static final double CENTER_RADIUS = 35;
    private static final int MAX_RINGS = 8;

    private static final Color[] PALETTE = {
            Color.rgb(0x34, 0x98, 0xDB), Color.rgb(0x2E, 0xCC, 0x71),
            Color.rgb(0xE7, 0x4C, 0x3C), Color.rgb(0x9B, 0x59, 0xB6),
            Color.rgb(0xF3, 0x9C, 0x12), Color.rgb(0x1A, 0xBC, 0x9C),
            Color.rgb(0xE6, 0x7E, 0x22), Color.rgb(0x34, 0x49, 0x5E)
    };

    private record Arc(double centerX, double centerY, double innerR, double outerR,
                       double startAngle, double sweepAngle, FileNode node) {}

    public RingsChart() {
        canvas = new Canvas();
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        getChildren().add(canvas);

        setMinSize(200, 200);

        canvas.setOnMouseClicked(this::onMouseClicked);

        widthProperty().addListener((obs, old, w) -> redraw());
        heightProperty().addListener((obs, old, h) -> redraw());
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
        currentArcs = null;
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void redraw() {
        if (root == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        var gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        double cx = canvas.getWidth() / 2;
        double cy = canvas.getHeight() / 2;
        double maxR = Math.min(cx, cy) - 10;

        currentArcs = computeArcs(root, cx, cy, maxR);
        drawArcs(gc, currentArcs);
    }

    private Arc[] computeArcs(FileNode node, double cx, double cy, double maxR) {
        var arcs = new ArrayList<Arc>();
        computeRing(node, cx, cy, 0, 0, 360, maxR, arcs);
        return arcs.toArray(new Arc[0]);
    }

    private void computeRing(FileNode node, double cx, double cy,
                             int depth, double startAngle, double sweepAngle,
                             double maxR, List<Arc> result) {
        if (depth >= MAX_RINGS || node == null || sweepAngle <= 0) return;

        double innerR = depth == 0 ? 0 : CENTER_RADIUS + (depth - 1) * RING_WIDTH;
        double outerR = depth == 0 ? CENTER_RADIUS : CENTER_RADIUS + depth * RING_WIDTH;

        if (outerR > maxR) return;

        result.add(new Arc(cx, cy, innerR, outerR, startAngle, sweepAngle, node));

        if (node.isLeaf() || node.getChildren().isEmpty()) return;

        long total = node.getChildren().stream().mapToLong(FileNode::getSize).sum();
        if (total == 0) return;

        double angle = startAngle;
        for (var child : node.getChildren()) {
            if (child.getSize() == 0) continue;
            double childSweep = (double) child.getSize() / total * sweepAngle;
            if (childSweep < 0.5) childSweep = 0.5;

            computeRing(child, cx, cy, depth + 1, angle, childSweep, maxR, result);
            angle += childSweep;
        }
    }

    private void drawArcs(GraphicsContext gc, Arc[] arcs) {
        for (var arc : arcs) {
            if (arc.node.getSize() == 0 && arc.node.isDirectory()) continue;

            Color fill = colorFor(arc.node, arc.innerR, arc.outerR, arc.centerX, arc.centerY);
            Color stroke = fill.darker();

            gc.setFill(fill);
            gc.setStroke(stroke);
            gc.setLineWidth(0.5);

            double outerR = arc.outerR;
            if (arc.innerR == 0) {
                gc.fillOval(arc.centerX - outerR, arc.centerY - outerR,
                        outerR * 2, outerR * 2);
                gc.strokeOval(arc.centerX - outerR, arc.centerY - outerR,
                        outerR * 2, outerR * 2);
            } else {
                gc.fillArc(arc.centerX - outerR, arc.centerY - outerR,
                        outerR * 2, outerR * 2,
                        arc.startAngle, arc.sweepAngle, ArcType.ROUND);
                gc.strokeArc(arc.centerX - outerR, arc.centerY - outerR,
                        outerR * 2, outerR * 2,
                        arc.startAngle, arc.sweepAngle, ArcType.ROUND);

                if (arc.innerR > 0 && arc.sweepAngle > 5) {
                    gc.setFill(Color.WHITE);
                    gc.setFont(javafx.scene.text.Font.font("SansSerif", 10));

                    double midAngle = Math.toRadians(arc.startAngle + arc.sweepAngle / 2);
                    double textR = (arc.innerR + outerR) / 2;
                    double tx = arc.centerX + textR * Math.cos(midAngle - Math.PI / 2);
                    double ty = arc.centerY + textR * Math.sin(midAngle - Math.PI / 2);

                    String label = arc.node.getName();
                    String size = MainWindow.formatSize(arc.node.getSize());
                    double textW = label.length() * 5;
                    if (textW < arc.sweepAngle / 360 * 2 * Math.PI * textR * 0.7) {
                        String full = label + "  " + size;
                        double fullW = full.length() * 5;
                        if (fullW < arc.sweepAngle / 360 * 2 * Math.PI * textR * 0.7) {
                            gc.fillText(full, tx - fullW / 2, ty + 4);
                        } else {
                            gc.fillText(label, tx - textW / 2, ty + 4);
                        }
                    }
                }
            }

            gc.setFill(Color.WHITE);
        }
    }

    private Color colorFor(FileNode node, double innerR, double outerR, double cx, double cy) {
        if (node.isDirectory()) {
            int hash = node.getName().hashCode();
            int idx = Math.abs(hash) % PALETTE.length;
            return PALETTE[idx].deriveColor(1, 0.7, 1, 1);
        }
        return FileTypeCategory.forFile(node.getName()).color();
    }

    private void onMouseClicked(MouseEvent e) {
        if (currentArcs == null) return;

        double dx = e.getX();
        double dy = e.getY();

        for (var arc : currentArcs) {
            if (!arc.node.isDirectory() || arc.innerR == 0) continue;

            double dist = Math.sqrt(Math.pow(dx - arc.centerX, 2) + Math.pow(dy - arc.centerY, 2));

            if (dist >= arc.innerR && dist <= arc.outerR) {
                double angle = Math.toDegrees(Math.atan2(dx - arc.centerX, -(dy - arc.centerY)));
                if (angle < 0) angle += 360;

                double start = arc.startAngle;
                double end = arc.startAngle + arc.sweepAngle;

                if (angle >= start && angle <= end) {
                    if (onNodeClicked != null) {
                        onNodeClicked.accept(arc.node);
                    }
                    return;
                }
            }
        }
    }
}
