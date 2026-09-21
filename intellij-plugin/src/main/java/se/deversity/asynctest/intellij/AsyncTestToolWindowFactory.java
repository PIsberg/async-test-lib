package se.deversity.asynctest.intellij;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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

        // The Refresh button the README and the plugin description promise. The action is
        // registered in plugin.xml, which only puts it in the Tools menu.
        AnAction refresh = ActionManager.getInstance().getAction("AsyncTest.Refresh");
        if (refresh != null) {
            toolWindow.setTitleActions(List.of(refresh));
        }

        panel.refresh();
    }
}
