package se.deversity.asynctest.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * Re-reads the JSON report file and updates the tool window.
 * Available via Tools menu and the tool window's own toolbar.
 */
public final class RefreshFindingsAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("async-test Findings");
        if (toolWindow == null) return;

        FindingsPanel panel = (FindingsPanel) toolWindow.getComponent()
            .getClientProperty("findingsPanel");
        if (panel != null) {
            panel.refresh();
        }

        toolWindow.show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
