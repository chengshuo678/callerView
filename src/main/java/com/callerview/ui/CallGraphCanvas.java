package com.callerview.ui;

import com.callerview.core.CallNode;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Self-contained, dependency-light call-chain graph drawn with plain Java2D.
 *
 * <p>Layout: horizontal tidy tree &mdash; the target method (root) on the left, callers
 * extending to the right. Leaves receive sequential y-coordinates; each parent is centred
 * over its children. Cycles are already broken by the analyser, so the result is a tree.</p>
 *
 * <p>Interactions: mouse wheel zoom (towards cursor), background-drag pan, click to select,
 * double-click to navigate. Supports programmatic selection (tree &harr; graph sync) and
 * fit-to-view on new results.</p>
 */
public class CallGraphCanvas extends JComponent {

    // ---- layout (model space) ----
    private static final double COL_GAP = 250;
    private static final double LEAF_GAP = 86;
    private static final double PADDING = 60;
    private static final double HPAD = 12;
    private static final double VPAD = 8;
    private static final double LINE_GAP = 3;
    private static final double MAX_NODE_W = 300;
    private static final double MIN_ZOOM = 0.12;
    private static final double MAX_ZOOM = 4.0;

    // ---- colours (theme aware) ----
    private static final Color BG = JBColor.background();
    private static final Color NORMAL_FILL = new JBColor(0xEAF4FF, 0x2D3748);
    private static final Color NORMAL_BORDER = new JBColor(0x2B6CB0, 0x78A0D2);
    private static final Color PATH_FILL = new JBColor(0xFFE3E3, 0x3C1E1E);
    private static final Color PATH_BORDER = new JBColor(0xC53030, 0xFF8A8A);
    private static final Color CORE_FILL_TOP = new JBColor(0xFF6B6B, 0xE03131);
    private static final Color CORE_FILL_BOTTOM = new JBColor(0xE03131, 0x9B1C1C);
    private static final Color CORE_BORDER = new JBColor(0x9B1C1C, 0xFF9F9F);
    private static final Color ROOT_BORDER = new JBColor(0xE08600, 0xFFB454);
    private static final Color EDGE = new JBColor(0x96A0AA, 0x5A6470);
    private static final Color EDGE_CORE = new JBColor(0xE03131, 0xFF7A7A);
    private static final Color SELECTION = new JBColor(0xFF8C00, 0xFFB400);
    private static final Color TEXT_GRAY = JBColor.GRAY;

    private @Nullable CallNode root;
    private final Map<CallNode, Rectangle2D.Double> bounds = new HashMap<>();
    private final Map<CallNode, Double> widths = new HashMap<>();
    private double modelMinX, modelMinY, modelMaxX, modelMaxY;

    private double zoom = 1.0;
    private final Point2D.Double pan = new Point2D.Double(0, 0);

    private @Nullable CallNode selected;
    private @Nullable CallNode hovered;
    private @Nullable Consumer<CallNode> selectionListener;
    private @Nullable Consumer<CallNode> navigationListener;

    private final Font boldFont;
    private final Font smallFont;
    private final FontMetrics boldFm;
    private final FontMetrics smallFm;
    private final double nodeHeight;

    private Point panAnchor;
    private double panAnchorX, panAnchorY;

    public CallGraphCanvas() {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        boldFont = base.deriveFont(Font.BOLD);
        smallFont = base.deriveFont(Font.PLAIN, Math.max(9f, base.getSize() - 2f));
        boldFm = getFontMetrics(boldFont);
        smallFm = getFontMetrics(smallFont);
        nodeHeight = VPAD + boldFm.getHeight() + LINE_GAP + smallFm.getHeight() + VPAD;

        setOpaque(true);
        setBackground(BG);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        MouseInputAdapter adapter = new MouseInputAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                CallNode n = nodeAt(e);
                if (n == null) {
                    panAnchor = e.getPoint();
                    panAnchorX = pan.x;
                    panAnchorY = pan.y;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (panAnchor != null) {
                    pan.x = panAnchorX + (e.getX() - panAnchor.x);
                    pan.y = panAnchorY + (e.getY() - panAnchor.y);
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                panAnchor = null;
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                CallNode n = nodeAt(e);
                if (n != hovered) {
                    hovered = n;
                    setToolTipText(n == null ? null : nodeTooltip(n));
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                CallNode n = nodeAt(e);
                if (n != null) {
                    setSelected(n);
                    if (e.getClickCount() >= 2 && navigationListener != null) {
                        navigationListener.accept(n);
                    }
                }
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
        addMouseWheelListener(e -> {
            double factor = Math.pow(1.15, -e.getWheelRotation());
            zoomAt(e.getX(), e.getY(), factor);
        });
    }

    // ---- public API ----

    public void setRoot(@Nullable CallNode root) {
        this.root = root;
        this.selected = null;
        this.hovered = null;
        computeLayout();
        fitToView();
        repaint();
    }

    public void setSelectionListener(@Nullable Consumer<CallNode> listener) {
        this.selectionListener = listener;
    }

    public void setNavigationListener(@Nullable Consumer<CallNode> listener) {
        this.navigationListener = listener;
    }

    public void setSelected(@Nullable CallNode node) {
        if (selected == node) {
            return;
        }
        selected = node;
        repaint();
        if (selectionListener != null) {
            selectionListener.accept(node);
        }
    }

    /** Called from the sibling tree panel to avoid a feedback loop. */
    public void setSelectedSilent(@Nullable CallNode node) {
        if (selected == node) {
            return;
        }
        selected = node;
        scrollToNode(node);
        repaint();
    }

    public void zoomIn() {
        zoomAt(getWidth() / 2, getHeight() / 2, 1.2);
    }

    public void zoomOut() {
        zoomAt(getWidth() / 2, getHeight() / 2, 1 / 1.2);
    }

    public void resetView() {
        zoom = 1.0;
        pan.setLocation(0, 0);
        fitToView();
        repaint();
    }

    // ---- layout ----

    private void computeLayout() {
        bounds.clear();
        widths.clear();
        modelMinX = modelMinY = Double.POSITIVE_INFINITY;
        modelMaxX = modelMaxY = Double.NEGATIVE_INFINITY;
        if (root == null) {
            return;
        }
        // Column gap must clear the widest node (and its half-widths on both sides) plus a
        // margin, otherwise wide nodes in adjacent columns overlap and the connecting edge /
        // arrowhead gets drawn underneath a neighbouring node ("edge occlusion").
        double colGap = Math.max(COL_GAP, maxNodeWidth(root) + 2 * HPAD + 40);
        double[] leafCursor = {0};
        layoutNode(root, 0, colGap, leafCursor);
    }

    /** Widest node label across the whole tree (instance method: uses {@link #measureWidth}). */
    private double maxNodeWidth(CallNode node) {
        double w = measureWidth(node);
        for (CallNode child : node.getChildren()) {
            w = Math.max(w, maxNodeWidth(child));
        }
        return w;
    }

    private double layoutNode(CallNode node, int depth, double colGap, double[] leafCursor) {
        double w = measureWidth(node);
        widths.put(node, w);
        double x = PADDING + depth * colGap;
        double y;
        if (node.getChildren().isEmpty()) {
            y = PADDING + leafCursor[0] * LEAF_GAP;
            leafCursor[0]++;
        } else {
            double sum = 0;
            for (CallNode child : node.getChildren()) {
                sum += layoutNode(child, depth + 1, colGap, leafCursor);
            }
            y = sum / node.getChildren().size();
        }
        double h = nodeHeight;
        bounds.put(node, new Rectangle2D.Double(x - w / 2, y - h / 2, w, h));
        modelMinX = Math.min(modelMinX, x - w / 2);
        modelMaxX = Math.max(modelMaxX, x + w / 2);
        modelMinY = Math.min(modelMinY, y - h / 2);
        modelMaxY = Math.max(modelMaxY, y + h / 2);
        return y;
    }

    private double measureWidth(CallNode node) {
        double w = Math.max(boldFm.stringWidth(line1(node)), smallFm.stringWidth(line2(node)));
        return Math.min(MAX_NODE_W, w + 2 * HPAD);
    }

    // ---- paint ----

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(BG);
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (root == null) {
                g2.setColor(JBColor.foreground().brighter().brighter().brighter());
                Font f = g2.getFont().deriveFont(Font.PLAIN, 14f);
                g2.setFont(f);
                FontMetrics fm = g2.getFontMetrics(f);
                String msg = "Right-click a method → CallerView: Show Call Chain";
                String msg2 = "The caller chain of that method will be visualized here.";
                int w = Math.max(fm.stringWidth(msg), fm.stringWidth(msg2));
                int x = (getWidth() - w) / 2;
                int y = getHeight() / 2 - fm.getHeight();
                g2.drawString(msg, x, y);
                g2.drawString(msg2, x, y + fm.getHeight() + 4);
                return;
            }

            AffineTransform original = g2.getTransform();
            g2.transform(AffineTransform.getTranslateInstance(pan.x, pan.y));
            g2.transform(AffineTransform.getScaleInstance(zoom, zoom));
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            drawEdges(g2);
            drawNodes(g2);

            g2.setTransform(original);
            drawOverlay(g2);
        } finally {
            g2.dispose();
        }
    }

    private void drawEdges(Graphics2D g2) {
        Stroke stroke = new BasicStroke(1.6f);
        g2.setStroke(stroke);
        for (CallNode parent : bounds.keySet()) {
            for (CallNode child : parent.getChildren()) {
                Rectangle2D.Double pb = bounds.get(parent);
                Rectangle2D.Double cb = bounds.get(child);
                if (pb == null || cb == null) {
                    continue;
                }
                double x1 = cb.getX();                       // child left edge (caller, on the right)
                double y1 = cb.getCenterY();
                double x2 = pb.getX() + pb.getWidth();       // parent right edge (callee, on the left)
                double y2 = pb.getCenterY();
                double cx = (x1 + x2) / 2;
                CubicCurve2D curve = new CubicCurve2D.Double(x1, y1, cx, y1, cx, y2, x2, y2);

                boolean coreEdge = child.isHasCoreBelow() || parent.isCoreAbove();
                g2.setColor(coreEdge ? EDGE_CORE : EDGE);
                g2.draw(curve);

                // arrowhead at the callee (parent) end, pointing left.
                GeneralPath arrow = new GeneralPath();
                double ah = 7;
                arrow.moveTo(x2, y2);
                arrow.lineTo(x2 + ah, y2 - ah / 2);
                arrow.lineTo(x2 + ah, y2 + ah / 2);
                arrow.closePath();
                g2.fill(arrow);
            }
        }
    }

    private void drawNodes(Graphics2D g2) {
        for (Map.Entry<CallNode, Rectangle2D.Double> entry : bounds.entrySet()) {
            CallNode node = entry.getKey();
            Rectangle2D.Double r = entry.getValue();
            drawNode(g2, node, r);
        }
    }

    private void drawNode(Graphics2D g2, CallNode node, Rectangle2D.Double r) {
        boolean core = node.isCore();
        boolean onPath = node.isOnCorePath();
        boolean isRoot = node == root;

        Color fillTop;
        Color fillBottom;
        Color border;
        if (core) {
            fillTop = CORE_FILL_TOP;
            fillBottom = CORE_FILL_BOTTOM;
            border = CORE_BORDER;
        } else if (onPath) {
            fillTop = PATH_FILL;
            fillBottom = PATH_FILL;
            border = PATH_BORDER;
        } else if (isRoot) {
            fillTop = NORMAL_FILL;
            fillBottom = NORMAL_FILL;
            border = ROOT_BORDER;
        } else {
            fillTop = NORMAL_FILL;
            fillBottom = NORMAL_FILL;
            border = NORMAL_BORDER;
        }

        double w = r.getWidth();
        double h = r.getHeight();
        double x = r.getX();
        double y = r.getY();
        double arc = Math.min(14, h);

        // body
        GradientPaint paint = new GradientPaint(
                (float) x, (float) y, fillTop,
                (float) x, (float) (y + h), fillBottom);
        g2.setPaint(paint);
        g2.fill(new RoundRectangle2D.Double(x, y, w, h, arc, arc));

        // border
        Stroke old = g2.getStroke();
        if (node == selected) {
            g2.setStroke(new BasicStroke(2.4f));
            g2.setColor(SELECTION);
            g2.draw(new RoundRectangle2D.Double(x - 1, y - 1, w + 2, h + 2, arc + 1, arc + 1));
        }
        g2.setStroke(new BasicStroke(node == hovered ? 1.8f : 1.2f));
        g2.setColor(node == hovered ? border.brighter() : border);
        g2.draw(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
        g2.setStroke(old);

        // text
        double contentW = w - 2 * HPAD;
        g2.setColor(isReadable(fillTop) ? JBColor.foreground() : Color.WHITE);
        g2.setFont(boldFont);
        FontMetrics fm = g2.getFontMetrics();
        String l1 = truncate(line1(node), fm, contentW);
        g2.drawString(l1, (float) (x + HPAD), (float) (y + VPAD + fm.getAscent()));

        g2.setFont(smallFont);
        FontMetrics fm2 = g2.getFontMetrics();
        g2.setColor(core ? Color.WHITE.darker() : TEXT_GRAY);
        String l2 = truncate(line2(node), fm2, contentW);
        g2.drawString(l2, (float) (x + HPAD), (float) (y + VPAD + fm.getHeight() + LINE_GAP + fm2.getAscent()));
    }

    private void drawOverlay(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int boxX = 10;
        int boxY = 10;
        int lineH = g2.getFontMetrics().getHeight();
        Font font = g2.getFont().deriveFont(Font.PLAIN, Math.max(10f, g2.getFont().getSize() - 1f));
        g2.setFont(font);

        String[] lines = {
                "◆ Target method (★) on the left · callers extend to the right  ⟶ child calls parent",
                "■ Red = chains affecting a core method    Zoom " + Math.round(zoom * 100) + "%"
        };
        int boxW = 0;
        for (String l : lines) {
            boxW = Math.max(boxW, g2.getFontMetrics().stringWidth(l));
        }
        int boxH = lines.length * lineH + 10;

        g2.setColor(new JBColor(0xF7F7F7, 0x2A2D33));
        g2.fillRoundRect(boxX, boxY, boxW + 16, boxH, 8, 8);
        g2.setColor(new JBColor(0xCCCCCC, 0x3A3D43));
        g2.drawRoundRect(boxX, boxY, boxW + 16, boxH, 8, 8);

        g2.setColor(JBColor.foreground());
        for (int i = 0; i < lines.length; i++) {
            g2.drawString(lines[i], boxX + 8, boxY + 5 + lineH * (i + 1) - 3);
        }
    }

    // ---- helpers ----

    private @NotNull String line1(CallNode node) {
        return (node == root ? "★ " : "") + node.getMethodName() + "(" + paramsOf(node) + ")";
    }

    private @NotNull String line2(CallNode node) {
        String fqn = node.getPackageFqn();
        String text = (fqn != null && !fqn.isEmpty()) ? fqn : node.getClassName();
        if (node.isTruncated()) {
            text += "  …";
        }
        return text;
    }

    private @NotNull String paramsOf(CallNode node) {
        String sig = node.getSignature();
        int l = sig.indexOf('(');
        int r = sig.lastIndexOf(')');
        if (l >= 0 && r > l) {
            return sig.substring(l + 1, r);
        }
        return "";
    }

    private @Nullable String nodeTooltip(CallNode node) {
        return "<html>" + escape(node.getSignature()) +
                (node.isCore() ? "  <b style='color:red'>(core)</b>" : "") +
                (node.isTruncated() ? "  <i>(truncated)</i>" : "") + "</html>";
    }

    private static @NotNull String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private @NotNull String truncate(String s, FontMetrics fm, double maxW) {
        if (fm.stringWidth(s) <= maxW) {
            return s;
        }
        String ell = "…";
        double ew = fm.stringWidth(ell);
        while (!s.isEmpty() && fm.stringWidth(s) + ew > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + ell;
    }

    private static boolean isReadable(Color fill) {
        // simple luminance test to pick readable text colour
        double lum = 0.299 * fill.getRed() + 0.587 * fill.getGreen() + 0.114 * fill.getBlue();
        return lum > 140;
    }

    private @Nullable CallNode nodeAt(MouseEvent e) {
        if (root == null) {
            return null;
        }
        double mx = (e.getX() - pan.x) / zoom;
        double my = (e.getY() - pan.y) / zoom;
        for (Map.Entry<CallNode, Rectangle2D.Double> entry : bounds.entrySet()) {
            if (entry.getValue().contains(mx, my)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void zoomAt(int sx, int sy, double factor) {
        double newZoom = clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
        if (newZoom == zoom) {
            return;
        }
        // keep the model point under the cursor fixed
        double mx = (sx - pan.x) / zoom;
        double my = (sy - pan.y) / zoom;
        zoom = newZoom;
        pan.x = sx - mx * zoom;
        pan.y = sy - my * zoom;
        repaint();
    }

    private void fitToView() {
        if (root == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        double mw = (modelMaxX - modelMinX) + PADDING * 2;
        double mh = (modelMaxY - modelMinY) + PADDING * 2;
        double zx = getWidth() / mw;
        double zy = getHeight() / mh;
        zoom = clamp(Math.min(zx, zy), MIN_ZOOM, 1.2);
        pan.x = (getWidth() - (modelMaxX - modelMinX) * zoom) / 2 - modelMinX * zoom;
        pan.y = (getHeight() - (modelMaxY - modelMinY) * zoom) / 2 - modelMinY * zoom;
    }

    private void scrollToNode(@Nullable CallNode node) {
        if (node == null) {
            return;
        }
        Rectangle2D.Double r = bounds.get(node);
        if (r == null) {
            return;
        }
        double cx = r.getCenterX() * zoom + pan.x;
        double cy = r.getCenterY() * zoom + pan.y;
        if (cx < 20 || cx > getWidth() - 20 || cy < 20 || cy > getHeight() - 20) {
            pan.x += getWidth() / 2.0 - cx;
            pan.y += getHeight() / 2.0 - cy;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
