package se.deversity.asynctest.intellij;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The plugin's UI layer inside a headless IDE (#723): the Refresh action the README promises, the
 * tool window content and its title action, a report found under the project and the settings page.
 * Before this, every test covered the JSON parser only, and the Refresh button was documented for the
 * plugin's whole life without existing.
 *
 * <p>JUnit 3 style ({@code testX} methods), which is what {@link BasePlatformTestCase} runs; the
 * vintage engine runs it beside the Jupiter tests.
 */
public class FindingsToolWindowPlatformTest extends BasePlatformTestCase {

    private static final String REPORT = "target/async-test-reports/async-test-report.json";

    public void testTheRefreshActionIsRegisteredAndInTheToolsMenu() {
        AnAction refresh = ActionManager.getInstance().getAction("AsyncTest.Refresh");
        assertInstanceOf(refresh, RefreshFindingsAction.class);

        DefaultActionGroup tools =
                (DefaultActionGroup) ActionManager.getInstance().getAction("ToolsMenu");
        // The group may hold a stub for the action rather than the loaded instance, so match by id.
        ActionManager actions = ActionManager.getInstance();
        assertTrue("Tools → Refresh async-test Findings, as the README says",
                Arrays.stream(tools.getChildActionsOrStubs())
                        .anyMatch(child -> "AsyncTest.Refresh".equals(actions.getId(child))));
    }

    public void testTheFactoryAddsThePanelAndTheRefreshTitleAction() {
        List<Content> contents = new ArrayList<>();
        List<AnAction> titleActions = new ArrayList<>();
        JPanel component = new JPanel();
        ToolWindow window = toolWindow(component, contents, titleActions);

        new AsyncTestToolWindowFactory().createToolWindowContent(getProject(), window);

        assertEquals("one content, the findings panel", 1, contents.size());
        assertInstanceOf(component.getClientProperty("findingsPanel"), FindingsPanel.class);
        assertEquals("the tool window toolbar carries the Refresh button",
                1, titleActions.size());
        assertInstanceOf(titleActions.get(0), RefreshFindingsAction.class);
    }

    public void testAReportUnderTheProjectIsLoadedIntoTheSummary() throws Exception {
        Path report = Path.of(getProject().getBasePath(), REPORT);
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                {"findings": [
                  {"detectorName": "DeadlockDetector", "severity": "CRITICAL", "timestampMs": 0, "report": "Deadlock"},
                  {"detectorName": "FalseSharingDetector", "severity": "HIGH", "timestampMs": 0, "report": "False sharing"}
                ]}
                """, StandardCharsets.UTF_8);
        try {
            FindingsPanel panel = new FindingsPanel(getProject());
            panel.refresh();

            String summary = summaryOf(panel);
            assertTrue(summary, summary.startsWith("2 finding(s) — 1 CRITICAL, 1 HIGH, 0 MEDIUM, 0 LOW"));
        } finally {
            Files.deleteIfExists(report);
        }
    }

    public void testWithNoReportTheSummarySaysWhereToLook() {
        FindingsPanel panel = new FindingsPanel(getProject());
        panel.refresh();

        assertTrue(summaryOf(panel).startsWith("Report file not found"));
    }

    public void testTheSettingsPageAppliesAndResetsTheReportPaths() {
        AsyncTestSettings settings = AsyncTestSettings.getInstance();
        String original = settings.getReportPathPattern();
        AsyncTestConfigurable page = new AsyncTestConfigurable();
        try {
            JTextField field = firstOf(page.createComponent(), JTextField.class);
            assertEquals(original, field.getText());
            assertFalse(page.isModified());

            field.setText("out/report.json");
            assertTrue(page.isModified());
            page.apply();
            assertEquals("out/report.json", settings.getReportPathPattern());

            field.setText("something/else.json");
            page.reset();
            assertEquals("out/report.json", field.getText());
            assertFalse(page.isModified());
        } finally {
            settings.setReportPathPattern(original);
        }
    }

    private static String summaryOf(FindingsPanel panel) {
        JComponent root = panel.getComponent();
        Component north = ((BorderLayout) root.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        return ((JLabel) north).getText();
    }

    private static <T extends Component> T firstOf(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = firstOf(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * A tool window that records what the factory gives it. The headless IDE's own tool windows
     * accept title actions and content without keeping them, so they cannot show what was added.
     */
    private ToolWindow toolWindow(JComponent component, List<Content> contents, List<AnAction> titleActions) {
        ContentManager contentManager = (ContentManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ContentManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addContent") && args != null && args.length >= 1) {
                        contents.add((Content) args[0]);
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
        return (ToolWindow) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ToolWindow.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getComponent" -> component;
                    case "getContentManager" -> contentManager;
                    case "setTitleActions" -> {
                        @SuppressWarnings("unchecked")
                        List<AnAction> actions = (List<AnAction>) args[0];
                        titleActions.addAll(actions);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == char.class) return '\0';
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return 0;
    }
}
