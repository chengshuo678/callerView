package com.callerview.toolwindow;

import com.callerview.ui.CallChainPanel;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Project-level service holding the single {@link CallChainPanel} instance shared by the tool
 * window factory and the {@code ShowCallChainAction}. The {@code Project} is injected into the
 * constructor by the platform (registered via {@code <projectService>} in plugin.xml).
 */
public class CallChainViewService {

    public static final String TOOL_WINDOW_ID = "CallerView";

    private final CallChainPanel panel;

    public CallChainViewService(@NotNull Project project) {
        this.panel = new CallChainPanel(project);
    }

    @NotNull
    public CallChainPanel getPanel() {
        return panel;
    }
}
