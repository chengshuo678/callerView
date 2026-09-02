package com.callerview.ui;

import com.callerview.core.CallChainAnalyzer;
import com.callerview.core.CallNode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * The tool-window root component: a horizontal split with the graph on the left and the
 * side tree on the right, plus a tiny toolbar (zoom controls + result info).
 *
 * <p>Drives the whole pipeline: on {@link #analyze} it runs the analyser on a background task,
 * marks core chains, then pushes the result to both views and wires their selection/navigation
 * callbacks together.</p>
 */
public class CallChainPanel extends JPanel {

    private static final Logger LOG = Logger.getInstance(CallChainPanel.class);

    private final @NotNull Project project;
    private final CallGraphCanvas canvas = new CallGraphCanvas();
    private final CallChainTreePanel tree = new CallChainTreePanel();
    private final JLabel infoLabel = new JLabel(" ");

    public CallChainPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        JPanel graphHolder = new JPanel(new BorderLayout());
        graphHolder.add(buildToolbar(), BorderLayout.NORTH);
        graphHolder.add(canvas, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphHolder, tree);
        split.setResizeWeight(0.7);
        split.setDividerLocation(0.7);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);

        // Bi-directional selection sync (both "silent" to avoid loops).
        canvas.setSelectionListener(tree::setSelectedSilent);
        tree.setSelectionListener(canvas::setSelectedSilent);
        canvas.setNavigationListener(this::navigate);
        tree.setNavigationListener(this::navigate);
    }

    private JComponent buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorderPainted(false);
        tb.add(new JLabel("CallerView  "));
        tb.add(button("放大", e -> canvas.zoomIn()));
        tb.add(button("缩小", e -> canvas.zoomOut()));
        tb.add(button("重置视图", e -> canvas.resetView()));
        tb.add(Box.createHorizontalGlue());
        infoLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        tb.add(infoLabel);
        return tb;
    }

    private JButton button(String text, ActionListener listener) {
        JButton b = new JButton(text);
        b.addActionListener(listener);
        b.setFocusable(false);
        b.setMargin(new Insets(0, 6, 0, 6));
        return b;
    }

    /** Entry point invoked from {@code ShowCallChainAction}. */
    public void analyze(@Nullable final PsiMethod method) {
        if (method == null) {
            return;
        }
        infoLabel.setText("分析中…");
        infoLabel.repaint();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "CallerView: analyzing callers", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                CallNode root = null;
                String error = null;
                try {
                    CallChainAnalyzer analyzer = new CallChainAnalyzer(project);
                    root = analyzer.analyze(method, indicator);
                    if (root != null) {
                        CallChainAnalyzer.markCoreBelow(root);
                        CallChainAnalyzer.markCoreAbove(root, false);
                    }
                } catch (ProcessCanceledException ignored) {
                    // user pressed cancel — leave the current view untouched.
                    return;
                } catch (Throwable t) {
                    LOG.error("CallerView: analysis failed", t);
                    error = t.getMessage();
                }
                final CallNode result = root;
                final String err = error;
                ApplicationManager.getApplication().invokeLater(() -> showResult(result, err));
            }

            @Override
            public void onSuccess() {
            }
        });
    }

    private void showResult(@Nullable CallNode root, @Nullable String error) {
        canvas.setRoot(root);
        tree.setRoot(root);
        if (error != null) {
            infoLabel.setText("分析失败: " + error);
            return;
        }
        if (root == null) {
            infoLabel.setText("无结果");
            return;
        }
        int count = countNodes(root);
        String text = "目标: " + root.getShortId() + "    节点数: " + count;
        if (hasTruncated(root)) {
            text += "    （部分调用链因深度/规模限制被截断）";
        }
        infoLabel.setText(text);
    }

    private static boolean hasTruncated(CallNode node) {
        if (node.isTruncated()) {
            return true;
        }
        for (CallNode child : node.getChildren()) {
            if (hasTruncated(child)) {
                return true;
            }
        }
        return false;
    }

    private static int countNodes(CallNode node) {
        int c = 1;
        for (CallNode child : node.getChildren()) {
            c += countNodes(child);
        }
        return c;
    }

    private void navigate(@Nullable CallNode node) {
        if (node == null) {
            return;
        }
        final PsiMethod method = node.getPsiMethod();
        if (method == null) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            boolean valid = ApplicationManager.getApplication()
                    .runReadAction((Computable<Boolean>) method::isValid);
            if (valid) {
                method.navigate(true);
            }
        });
    }
}
