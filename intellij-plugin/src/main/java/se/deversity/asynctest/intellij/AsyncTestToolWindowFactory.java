package se.deversity.asynctest.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the "async-test Findings" tool window content when IntelliJ first opens it.
 */
public final class AsyncTestToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        FindingsPanel panel = new FindingsPanel(project);
        toolWindow.getComponent().putClientProperty("findingsPanel", panel);

        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(panel.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);

        panel.refresh();
    }
}
