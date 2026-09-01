package com.callerview.toolwindow;

import com.callerview.CallerViewIcons;
import com.callerview.ui.CallChainPanel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the single content (the shared {@link CallChainPanel}) for the "CallerView" tool
 * window declared in plugin.xml. Sets the tool-window icon programmatically (version-robust).
 */
public class CallChainToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // The panel is a shared project-service instance; if the factory is invoked more than
        // once (tool-window re-creation), adding the same Swing component twice would throw.
        if (toolWindow.getContentManager().getContentCount() > 0) {
            return;
        }
        CallChainPanel panel = project.getService(CallChainViewService.class).getPanel();
        ContentFactory factory = ApplicationManager.getApplication().getService(ContentFactory.class);
        Content content = factory.createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
        toolWindow.setIcon(CallerViewIcons.TOOL_WINDOW);
    }
}
