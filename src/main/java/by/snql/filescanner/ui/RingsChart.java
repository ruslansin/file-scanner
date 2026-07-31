package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RingsChart extends StackPane {

    private final Canvas canvas;
    private FileNode root;
    private Consumer<FileNode> onNodeClicked;
    private RingSegment[] currentSegments;

    private static final double RING_WIDTH = 36;
    private static final double CENTER_RADIUS = 60;
    private static final int MAX_RINGS = 6;

    private static final Color[] PALETTE = {
            Color.rgb(0x34, 0x98, 0xDB), Color.rgb(0x2E, 0xCC, 0x71),
            Color.rgb(0xE7, 0x4C, 0x3C), Color.rgb(0x9B, 0x59, 0xB6),
            Color.rgb(0xF3, 0x9C, 0x12), Color.rgb(0x1A, 0xBC, 0x9C),
            Color.rgb(0xE6, 0x7E, 0x22), Color.rgb(0x34, 0x49, 0x5E),
            Color.rgb(0xC0, 0x39, 0x2B), Color.rgb(0x8E, 0x44, 0xAD)
    };

    private record RingSegment(double centerX, double centerY, double innerR, double outerR,
                                double startAngle, double sweepAngle, FileNode node, String label) {}

    public RingsChart() {
        canvas = new Canvas();
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        getChildren().add(canvas);

        setMinSize(200, 200);

        canvas.setOnMouseClicked(this::onMouseClicked);
        canvas.setOnMouseMoved(this::onMouseMoved);

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
        currentSegments = null;
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void redraw() {
        if (root == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        var gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        double cx = canvas.getWidth() / 2;
        double cy = canvas.getHeight() / 2;
        double maxR = Math.min(cx, cy) - 10;

        currentSegments = computeSegments(root, cx, cy, maxR);
        draw(gc, currentSegments);
    }

    private RingSegment[] computeSegments(FileNode node, double cx, double cy, double maxR) {
        var segs = new ArrayList<RingSegment>();
        computeRing(node, cx, cy, 0, 0, 360, maxR, segs);
        return segs.toArray(new RingSegment[0]);
    }

    private void computeRing(FileNode node, double cx, double cy,
                             int depth, double startAngle, double sweepAngle,
                             double maxR, List<RingSegment> result) {
        if (depth >= MAX_RINGS || node == null || sweepAngle <= 0) return;

        double innerR = depth == 0 ? 0 : CENTER_RADIUS + (depth - 1) * RING_WIDTH;
        double outerR = depth == 0 ? CENTER_RADIUS : CENTER_RADIUS + depth * RING_WIDTH;

        if (outerR > maxR) return;

        result.add(new RingSegment(cx, cy, innerR, outerR, startAngle, sweepAngle, node,
                node.getName()));

        if (node.isLeaf() || node.getChildren().isEmpty()) return;

        var children = new ArrayList<>(node.getChildren());
        children.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));

        long total = children.stream().mapToLong(FileNode::getSize).sum();
        if (total == 0) return;

        double angle = startAngle;
        var prevAngles = new ArrayList<Double>();
        for (int i = 0; i < children.size(); i++) {
            var child = children.get(i);
            double childSweep = (double) child.getSize() / total * sweepAngle;
            if (childSweep < 2.0) continue;
            prevAngles.add(angle);
            computeRing(child, cx, cy, depth + 1, angle, childSweep, maxR, result);
            angle += childSweep;
        }
    }

    private void draw(GraphicsContext gc, RingSegment[] segs) {
        for (var seg : segs) {
            if (seg.node.getSize() == 0 && seg.node.isDirectory()) continue;

            Color fill = colorFor(seg.node, seg.innerR);
            Color stroke = fill.darker();

            gc.setFill(fill);
            gc.setStroke(stroke);
            gc.setLineWidth(0.5);

            if (seg.innerR == 0) {
                double r = seg.outerR;
                gc.fillOval(seg.centerX - r, seg.centerY - r, r * 2, r * 2);
                gc.strokeOval(seg.centerX - r, seg.centerY - r, r * 2, r * 2);
            } else {
                double r = seg.outerR;
                gc.fillArc(seg.centerX - r, seg.centerY - r,
                        r * 2, r * 2,
                        seg.startAngle, seg.sweepAngle, ArcType.ROUND);
                gc.strokeArc(seg.centerX - r, seg.centerY - r,
                        r * 2, r * 2,
                        seg.startAngle, seg.sweepAngle, ArcType.ROUND);

                drawArcLabel(gc, seg);
            }
        }

        if (root != null) {
            double r = CENTER_RADIUS * 0.7;
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("SansSerif", 11));
            gc.setTextAlign(TextAlignment.CENTER);
            String label = root.getName();
            if (label.length() > 10) label = label.substring(0, 9) + "…";
            gc.fillText(label, root != null ? canvas.getWidth() / 2 : 0,
                    root != null ? canvas.getHeight() / 2 + 4 : 0);
            gc.setTextAlign(TextAlignment.LEFT);
        }
    }

    private void drawArcLabel(GraphicsContext gc, RingSegment seg) {
        if (seg.sweepAngle < 5) return;

        double midAngle = Math.toRadians(seg.startAngle + seg.sweepAngle / 2);
        double textR = (seg.innerR + seg.outerR) / 2;

        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("SansSerif", 10));

        String label = seg.node.getName();
        String size = MainWindow.formatSize(seg.node.getSize());

        double arcLen = 2 * Math.PI * textR * seg.sweepAngle / 360;
        double charW = gc.getFont().getSize() * 0.5;

        double tx = seg.centerX + textR * Math.cos(midAngle - Math.PI / 2);
        double ty = seg.centerY + textR * Math.sin(midAngle - Math.PI / 2);

        if (arcLen > label.length() * charW + 20) {
            String full = label + "  " + size;
            if (arcLen > full.length() * charW) {
                gc.fillText(full, tx, ty + 4);
            } else {
                gc.fillText(label, tx, ty + 4);
            }
        }
    }

    private Color colorFor(FileNode node, double innerR) {
        if (node.isDirectory()) {
            int hash = node.getName().hashCode();
            int idx = Math.abs(hash) % PALETTE.length;
            return PALETTE[idx].deriveColor(1, 0.65, 1, 1);
        }
        return FileTypeCategory.forFile(node.getName()).color().deriveColor(1, 0.9, 1, 1);
    }

    private void onMouseClicked(MouseEvent e) {
        if (currentSegments == null) return;
        double dx = e.getX(), dy = e.getY();

        for (var seg : currentSegments) {
            if (!seg.node.isDirectory() || seg.innerR == 0) continue;
            double dist = Math.sqrt(Math.pow(dx - seg.centerX, 2) + Math.pow(dy - seg.centerY, 2));
            if (dist < seg.innerR || dist > seg.outerR) continue;

            double angle = Math.toDegrees(Math.atan2(dx - seg.centerX, -(dy - seg.centerY)));
            if (angle < 0) angle += 360;
            if (angle >= seg.startAngle && angle <= seg.startAngle + seg.sweepAngle) {
                if (onNodeClicked != null) onNodeClicked.accept(seg.node);
                return;
            }
        }
    }

    private void onMouseMoved(MouseEvent e) {
        if (currentSegments == null) {
            canvas.setCursor(javafx.scene.Cursor.DEFAULT);
            return;
        }
        double dx = e.getX(), dy = e.getY();
        for (var seg : currentSegments) {
            if (!seg.node.isDirectory() || seg.innerR == 0) continue;
            double dist = Math.sqrt(Math.pow(dx - seg.centerX, 2) + Math.pow(dy - seg.centerY, 2));
            if (dist >= seg.innerR && dist <= seg.outerR) {
                double angle = Math.toDegrees(Math.atan2(dx - seg.centerX, -(dy - seg.centerY)));
                if (angle < 0) angle += 360;
                if (angle >= seg.startAngle && angle <= seg.startAngle + seg.sweepAngle) {
                    canvas.setCursor(javafx.scene.Cursor.HAND);
                    return;
                }
            }
        }
        canvas.setCursor(javafx.scene.Cursor.DEFAULT);
    }
}
