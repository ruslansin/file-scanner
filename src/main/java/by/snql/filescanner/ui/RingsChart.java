package by.snql.filescanner.ui;

import by.snql.filescanner.config.Settings;
import by.snql.filescanner.core.util.SizeFormat;
import by.snql.filescanner.model.FileNode;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sunburst / rings chart. Internally, angles are tracked in a "compass" convention
 * (0 degrees = 12 o'clock / north, increasing clockwise) since that is the natural
 * reading order for a sunburst and is what the mouse hit-test uses. Drawing with
 * JavaFX's {@code GraphicsContext} arc methods (0 degrees = 3 o'clock / east,
 * increasing counter-clockwise) requires converting at the point of drawing —
 * see {@link #toJfxStart} / {@link #toJfxExtent}.
 */
public class RingsChart extends StackPane {

    private final Canvas canvas;
    private final Tooltip tooltip = new Tooltip();
    private FileNode root;
    private Consumer<FileNode> onNodeClicked;
    private RingSegment[] currentSegments;
    private boolean redrawScheduled;

    private static final double RING_WIDTH = 36;
    private static final double CENTER_RADIUS = 60;

    /** GNOME Baobab-style depth cap — kept in sync with {@code TreemapLayout}'s render depth
     *  via {@code Settings.chartRenderDepth} ("Chart nesting depth" in Settings). Beyond this
     *  many rings, deeper content is aggregated into its ancestor ring rather than drawn as
     *  its own (increasingly thin, hard-to-read) band; click to drill down instead. The center
     *  disk counts as one "ring", so this is {@code chartRenderDepth + 1}. */
    private static int maxRings() {
        return Math.max(1, Settings.get().chartRenderDepth) + 1;
    }

    private static final Color[] PALETTE = {
            Color.rgb(0x34, 0x98, 0xDB), Color.rgb(0x2E, 0xCC, 0x71),
            Color.rgb(0xE7, 0x4C, 0x3C), Color.rgb(0x9B, 0x59, 0xB6),
            Color.rgb(0xF3, 0x9C, 0x12), Color.rgb(0x1A, 0xBC, 0x9C),
            Color.rgb(0xE6, 0x7E, 0x22), Color.rgb(0x34, 0x49, 0x5E),
            Color.rgb(0xC0, 0x39, 0x2B), Color.rgb(0x8E, 0x44, 0xAD)
    };

    /**
     * startAngle/sweepAngle are in the compass convention described on the class.
     * {@code truncated} is true when this node has children that aren't drawn as their own
     * ring — depth cap reached, out of physical radius, or some children too thin an angle
     * to render — so the UI can mark it instead of the chart just looking like a dead end.
     */
    private record RingSegment(double centerX, double centerY, double innerR, double outerR,
                                double startAngle, double sweepAngle, FileNode node, String label,
                                boolean truncated) {}

    public RingsChart() {
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
        int maxRings = maxRings();
        if (depth >= maxRings || node == null || sweepAngle <= 0) return;

        double innerR = depth == 0 ? 0 : CENTER_RADIUS + (depth - 1) * RING_WIDTH;
        double outerR = depth == 0 ? CENTER_RADIUS : CENTER_RADIUS + depth * RING_WIDTH;

        if (outerR > maxR) return;

        boolean hasChildren = !node.isLeaf() && !node.getChildren().isEmpty();
        boolean depthCapReached = depth + 1 >= maxRings;
        boolean noRoomForNextRing = outerR + RING_WIDTH > maxR;

        List<FileNode> children = null;
        long total = 0;
        boolean someChildTooThin = false;
        if (hasChildren) {
            children = new ArrayList<>(node.getChildren());
            children.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
            total = children.stream().mapToLong(FileNode::getSize).sum();
            if (total > 0) {
                for (var c : children) {
                    if ((double) c.getSize() / total * sweepAngle < 2.0) {
                        someChildTooThin = true;
                        break;
                    }
                }
            }
        }

        boolean truncated = hasChildren && total > 0
                && (depthCapReached || noRoomForNextRing || someChildTooThin);

        result.add(new RingSegment(cx, cy, innerR, outerR, startAngle, sweepAngle, node,
                node.getName(), truncated));

        if (!hasChildren || total == 0 || depthCapReached || noRoomForNextRing) return;

        double angle = startAngle;
        for (var child : children) {
            double childSweep = (double) child.getSize() / total * sweepAngle;
            if (childSweep < 2.0) continue;
            computeRing(child, cx, cy, depth + 1, angle, childSweep, maxR, result);
            angle += childSweep;
        }
    }

    private void draw(GraphicsContext gc, RingSegment[] segs) {
        // Draw in ascending radius order so that a ring never gets accidentally
        // painted over by an already-drawn ancestor sharing the same angular range.
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
                drawAnnulusWedge(gc, seg);
                drawArcLabel(gc, seg);
                drawTruncatedMarker(gc, seg);
            }
        }

        if (root != null) {
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("SansSerif", 11));
            gc.setTextAlign(TextAlignment.CENTER);
            String label = root.getName();
            if (label.length() > 10) label = label.substring(0, 9) + "…";
            gc.fillText(label, canvas.getWidth() / 2, canvas.getHeight() / 2 + 4);
            gc.setTextAlign(TextAlignment.LEFT);
        }
    }

    /**
     * Draws a proper ring (annulus) wedge — bounded between innerR and outerR —
     * rather than a full pie slice from the center, so nested rings remain visible
     * as concentric bands instead of overwriting their ancestors.
     */
    private void drawAnnulusWedge(GraphicsContext gc, RingSegment seg) {
        double jfxStart = toJfxStart(seg.startAngle);
        double jfxExtent = toJfxExtent(seg.sweepAngle);

        gc.beginPath();
        gc.arc(seg.centerX, seg.centerY, seg.outerR, seg.outerR, jfxStart, jfxExtent);
        gc.arc(seg.centerX, seg.centerY, seg.innerR, seg.innerR, jfxStart + jfxExtent, -jfxExtent);
        gc.closePath();
        gc.fill();
        gc.stroke();
    }

    /** Compass angle (0=N, clockwise+) to JavaFX arc angle (0=E, counter-clockwise+). */
    private static double toJfxStart(double compassAngle) {
        return 90 - compassAngle;
    }

    private static double toJfxExtent(double compassSweep) {
        return -compassSweep;
    }

    private void drawArcLabel(GraphicsContext gc, RingSegment seg) {
        if (seg.sweepAngle < 5) return;

        double midAngle = Math.toRadians(seg.startAngle + seg.sweepAngle / 2);
        double textR = (seg.innerR + seg.outerR) / 2;

        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("SansSerif", 10));

        String label = seg.node.getName();
        String size = by.snql.filescanner.core.util.SizeFormat.format(seg.node.getSize());

        double arcLen = 2 * Math.PI * textR * seg.sweepAngle / 360;
        double charW = gc.getFont().getSize() * 0.5;

        // Direction vector for compass angle theta (0=N,CW+): (sin theta, -cos theta).
        double tx = seg.centerX + textR * Math.sin(midAngle);
        double ty = seg.centerY - textR * Math.cos(midAngle);

        if (arcLen > label.length() * charW + 20) {
            String full = label + "  " + size;
            if (arcLen > full.length() * charW) {
                gc.fillText(full, tx, ty + 4);
            } else {
                gc.fillText(label, tx, ty + 4);
            }
        }
    }

    /**
     * Small dark arc along a truncated segment's outer edge — GNOME Baobab marks a ring
     * segment whose children were cut off by its own depth limit the same way, rather than
     * silently rendering nothing further and leaving the user unsure whether that folder
     * is really empty or just not expanded here. Click it to drill down and see more.
     */
    private void drawTruncatedMarker(GraphicsContext gc, RingSegment seg) {
        if (!seg.truncated()) return;
        double jfxStart = toJfxStart(seg.startAngle);
        double jfxExtent = toJfxExtent(seg.sweepAngle);

        gc.setStroke(Color.rgb(0, 0, 0, 0.55));
        gc.setLineWidth(2.5);
        gc.strokeArc(seg.centerX() - seg.outerR(), seg.centerY() - seg.outerR(),
                seg.outerR() * 2, seg.outerR() * 2, jfxStart, jfxExtent, ArcType.OPEN);
    }

    private Color colorFor(FileNode node, double innerR) {
        if (node.isDirectory()) {
            int hash = node.getName().hashCode();
            int idx = Math.abs(hash) % PALETTE.length;
            return PALETTE[idx].deriveColor(1, 0.65, 1, 1);
        }
        return FileTypeCategory.colorFor(node.getName()).deriveColor(1, 0.9, 1, 1);
    }

    private void onMouseClicked(MouseEvent e) {
        var seg = segmentAt(e.getX(), e.getY());
        if (seg != null && onNodeClicked != null) onNodeClicked.accept(seg.node);
    }

    private void onMouseMoved(MouseEvent e) {
        var dirSeg = segmentAt(e.getX(), e.getY());
        canvas.setCursor(dirSeg != null ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);

        var seg = anySegmentAt(e.getX(), e.getY());
        if (seg != null) {
            var node = seg.node;
            String path = node.getPath() != null ? node.getPath().toString() : node.getName();
            tooltip.setText(path + "\n" + SizeFormat.format(node.getSize()));
        }
    }

    private RingSegment segmentAt(double dx, double dy) {
        if (currentSegments == null) return null;
        for (var seg : currentSegments) {
            if (!seg.node.isDirectory() || seg.innerR == 0) continue;
            if (!segmentContains(seg, dx, dy)) continue;
            return seg;
        }
        return null;
    }

    /** Like {@link #segmentAt} but also matches leaf/file segments and the root's center
     *  disk — used for the hover tooltip, which should describe whatever is under the
     *  cursor rather than only clickable directories. */
    private RingSegment anySegmentAt(double dx, double dy) {
        if (currentSegments == null) return null;
        for (var seg : currentSegments) {
            if (!segmentContains(seg, dx, dy)) continue;
            return seg;
        }
        return null;
    }

    private static boolean segmentContains(RingSegment seg, double dx, double dy) {
        double dist = Math.sqrt(Math.pow(dx - seg.centerX, 2) + Math.pow(dy - seg.centerY, 2));
        if (dist < seg.innerR || dist > seg.outerR) return false;
        if (seg.innerR == 0) return true; // center disk — angle is irrelevant

        // Compass angle (0=N, clockwise+) of the point relative to the segment's center.
        double angle = Math.toDegrees(Math.atan2(dx - seg.centerX, -(dy - seg.centerY)));
        if (angle < 0) angle += 360;
        return angle >= seg.startAngle && angle <= seg.startAngle + seg.sweepAngle;
    }
}
