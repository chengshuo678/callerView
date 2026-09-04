package com.callerview.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings page shown under <b>Settings | Tools | CallerView</b>.
 *
 * <ul>
 *   <li>Upward depth (spinner, -1 = unlimited)</li>
 *   <li>Core methods list (one per line; ClassName.methodName or a FQN substring)</li>
 * </ul>
 */
public class CallerViewConfigurable implements Configurable {

    private static final String HINT =
            "<html>Examples (one per line; matching chains are highlighted in red):<br>" +
            "&nbsp;&nbsp;ClassName.methodName&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;matches <b>all overloads</b> of the method<br>" +
            "&nbsp;&nbsp;ClassName.methodName(int)&nbsp;&nbsp;&nbsp;matches <b>only</b> the overload with an int parameter<br>" +
            "&nbsp;&nbsp;com.foo.ClassName.methodName(int, String)&nbsp;&nbsp;full signature, exact match of a single overload<br>" +
            "&nbsp;&nbsp;(write parameter types as displayed in the graph, e.g. String / int / List&lt;String&gt;)</html>";

    private JPanel panel;
    private JSpinner depthSpinner;
    private JTextArea coreArea;

    @Nullable
    @Override
    public JComponent createComponent() {
        panel = new JPanel(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel depthLabel = new JLabel("Upward analysis depth (-1 = unlimited):");
        depthSpinner = new JSpinner(new SpinnerNumberModel(-1, -1, Integer.MAX_VALUE, 1));
        depthSpinner.setToolTipText("-1 means traverse all levels upward");

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(depthLabel, gbc);
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1;
        panel.add(depthSpinner, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        gbc.weighty = 1;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.VERTICAL;
        JLabel coreLabel = new JLabel("Core methods (one per line):");
        panel.add(coreLabel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        coreArea = new JTextArea();
        coreArea.setFont(UIManager.getFont("TextArea.font"));
        JScrollPane scroll = new JScrollPane(coreArea);
        scroll.setPreferredSize(new Dimension(460, 220));
        panel.add(scroll, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel hint = new JLabel(HINT);
        hint.setForeground(JBColor.GRAY);
        panel.add(hint, gbc);

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        CallerViewSettings settings = CallerViewSettings.getInstance();
        if ((Integer) depthSpinner.getValue() != settings.getMaxDepth()) {
            return true;
        }
        return !currentCoreList().equals(settings.getCoreMethods());
    }

    @Override
    public void apply() {
        CallerViewSettings settings = CallerViewSettings.getInstance();
        settings.setMaxDepth((Integer) depthSpinner.getValue());
        settings.setCoreMethods(currentCoreList());
    }

    @Override
    public void reset() {
        CallerViewSettings settings = CallerViewSettings.getInstance();
        depthSpinner.setValue(settings.getMaxDepth());
        StringBuilder sb = new StringBuilder();
        List<String> list = settings.getCoreMethods();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(list.get(i));
        }
        coreArea.setText(sb.toString());
        coreArea.setCaretPosition(0);
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        depthSpinner = null;
        coreArea = null;
    }

    @Override
    public String getDisplayName() {
        return "CallerView";
    }

    @Nullable
    @Override
    public String getHelpTopic() {
        return null;
    }

    private List<String> currentCoreList() {
        List<String> result = new ArrayList<>();
        if (coreArea == null) {
            return result;
        }
        String[] lines = coreArea.getText().split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }
}
