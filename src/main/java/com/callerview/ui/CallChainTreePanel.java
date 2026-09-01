package com.callerview.ui;

import com.callerview.core.CallNode;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Enumeration;
import java.util.function.Consumer;

/**
 * Side tree panel mirroring the call chain. Uses {@code setSelectedSilent} / a silent flag so
 * the graph &lt;-&gt; tree selection sync does not loop.
 */
public class CallChainTreePanel extends JPanel {

    private final JTree tree;
    private final DefaultTreeModel model;
    private final DefaultMutableTreeNode rootTreeNode;

    private @Nullable CallNode root;
    private boolean silent = false;
    private @Nullable Consumer<CallNode> selectionListener;
    private @Nullable Consumer<CallNode> navigationListener;

    public CallChainTreePanel() {
        super(new BorderLayout());
        rootTreeNode = new DefaultMutableTreeNode("(empty)");
        model = new DefaultTreeModel(rootTreeNode);
        tree = new JTree(model);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(1);
        tree.setLargeModel(true);
        tree.setCellRenderer(new CallNodeRenderer());
        add(new JScrollPane(tree), BorderLayout.CENTER);

        tree.addTreeSelectionListener(new TreeSelectionListener() {
            @Override
            public void valueChanged(TreeSelectionEvent e) {
                if (silent) {
                    return;
                }
                CallNode node = selectedNode();
                if (selectionListener != null && node != null) {
                    selectionListener.accept(node);
                }
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    CallNode node = selectedNode();
                    if (node != null && navigationListener != null) {
                        navigationListener.accept(node);
                    }
                }
            }
        });
    }

    public void setRoot(@Nullable CallNode root) {
        this.root = root;
        rootTreeNode.removeAllChildren();
        if (root != null) {
            rootTreeNode.setUserObject(root);
            build(root, rootTreeNode);
        } else {
            rootTreeNode.setUserObject("(empty)");
        }
        model.reload();
        expandAll();
    }

    public void setSelectionListener(@Nullable Consumer<CallNode> listener) {
        this.selectionListener = listener;
    }

    public void setNavigationListener(@Nullable Consumer<CallNode> listener) {
        this.navigationListener = listener;
    }

    /** Programmatic selection that does not fire the selection listener. */
    public void setSelectedSilent(@Nullable CallNode node) {
        if (rootTreeNode.getChildCount() == 0 && rootTreeNode.getUserObject() instanceof String) {
            return;
        }
        TreePath path = node == null ? null : find(rootTreeNode, node);
        silent = true;
        try {
            if (path == null) {
                tree.clearSelection();
            } else {
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
            }
        } finally {
            silent = false;
        }
    }

    private void build(CallNode node, DefaultMutableTreeNode parent) {
        DefaultMutableTreeNode tn = new DefaultMutableTreeNode(node);
        parent.add(tn);
        for (CallNode child : node.getChildren()) {
            build(child, tn);
        }
    }

    private void expandAll() {
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
    }

    private @Nullable CallNode selectedNode() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object last = path.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode) {
            Object u = ((DefaultMutableTreeNode) last).getUserObject();
            return u instanceof CallNode ? (CallNode) u : null;
        }
        return null;
    }

    private @Nullable TreePath find(TreeNode start, CallNode target) {
        Enumeration<TreeNode> e = ((DefaultMutableTreeNode) start).preorderEnumeration();
        while (e.hasMoreElements()) {
            TreeNode n = e.nextElement();
            if (n instanceof DefaultMutableTreeNode) {
                Object u = ((DefaultMutableTreeNode) n).getUserObject();
                if (u == target) {
                    return new TreePath(((DefaultMutableTreeNode) n).getPath());
                }
            }
        }
        return null;
    }

    private class CallNodeRenderer extends DefaultTreeCellRenderer {
        private final Color CORE = new JBColor(0xC53030, 0xFF8A8A);
        private final Color PATH = new JBColor(0xDD6B20, 0xFFB088);

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded,
                                                     boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
            setLeafIcon(null);
            setClosedIcon(null);
            setOpenIcon(null);
            if (value instanceof DefaultMutableTreeNode) {
                Object u = ((DefaultMutableTreeNode) value).getUserObject();
                if (u instanceof CallNode) {
                    CallNode n = (CallNode) u;
                    String label = n.getShortId() + (n == root ? "  ★" : "") + (n.isTruncated() ? " …" : "");
                    setText(label);
                    setToolTipText(n.getSignature());
                    if (!sel) {
                        if (n.isCore() || n.isOnCorePath()) {
                            setForeground(n.isCore() ? CORE : PATH);
                        }
                    }
                }
            }
            return this;
        }
    }
}
