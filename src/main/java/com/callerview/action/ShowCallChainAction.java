package com.callerview.action;

import com.callerview.CallerViewIcons;
import com.callerview.toolwindow.CallChainViewService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Registered in {@code EditorPopupMenu}. Locates the method at the caret, then opens the
 * CallerView tool window (activating it so the factory populates the content) and kicks off
 * the analysis.
 */
public class ShowCallChainAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(ShowCallChainAction.class);

    public ShowCallChainAction() {
        super("CallerView: Show Call Chain",
                "Analyze the callers of the method at the caret",
                CallerViewIcons.ACTION);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        final PsiMethod method = resolveMethod(e);
        if (method == null) {
            LOG.info("CallerView: no method resolved at the caret");
            Messages.showInfoMessage("No method found at the caret.\nPlace the caret inside a method and try again.", "CallerView");
            return;
        }
        String host = method.getContainingClass() == null
                ? method.getName()
                : method.getContainingClass().getQualifiedName() + "#" + method.getName();
        LOG.info("CallerView: analyzing callers of " + host);

        CallChainViewService service = project.getService(CallChainViewService.class);
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(CallChainViewService.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.activate(() -> service.getPanel().analyze(method));
        } else {
            service.getPanel().analyze(method);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        // This action is registered in EditorPopupMenu only, so it is always meaningful in an
        // editor. PSI-related data keys (PSI_FILE / PSI_ELEMENT) are not reliably populated
        // during the popup update pass in recent IDE builds (e.g. 2024.3); relying on them
        // here left the entry permanently gray. Enable it whenever a project is open and defer
        // all PSI work to actionPerformed(), where committed PSI and read access are guaranteed.
        e.getPresentation().setEnabled(true);
        e.getPresentation().setVisible(true);
    }

    private static @Nullable PsiMethod resolveMethod(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return null;
        }
        return ApplicationManager.getApplication().runReadAction((Computable<PsiMethod>) () -> {
            // 1) In the editor popup, PSI_ELEMENT is the element under the mouse (right-click),
            //    which is what the user actually targeted. Right-click does NOT move the caret.
            PsiElement mouseElement = e.getData(CommonDataKeys.PSI_ELEMENT);
            PsiMethod method = mouseElement == null
                    ? null
                    : PsiTreeUtil.getParentOfType(mouseElement, PsiMethod.class);
            if (method != null) {
                return method;
            }
            // 2) Fall back to the caret position.
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
            if (editor != null && file != null) {
                int offset = editor.getCaretModel().getOffset();
                PsiElement element = file.findElementAt(offset);
                method = element == null ? null : PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                if (method != null) {
                    return method;
                }
            }
            // 3) Last resort: a PsiMethod picked directly from the data context.
            return mouseElement instanceof PsiMethod ? (PsiMethod) mouseElement : null;
        });
    }
}
