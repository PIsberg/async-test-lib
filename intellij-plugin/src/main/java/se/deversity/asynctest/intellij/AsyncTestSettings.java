package se.deversity.asynctest.intellij;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persists async-test plugin settings across IDE restarts.
 */
@State(name = "AsyncTestSettings", storages = @Storage("asynctest.xml"))
public final class AsyncTestSettings implements PersistentStateComponent<AsyncTestSettings.State> {

    public static final class State {
        /** Glob patterns (comma-separated) used to locate the JSON report file. */
        public String reportPathPattern =
            "target/async-test-reports/async-test-report.json," +
            "build/async-test-reports/async-test-report.json";
    }

    private State state = new State();

    public static AsyncTestSettings getInstance() {
        return ApplicationManager.getApplication().getService(AsyncTestSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        this.state = loaded;
    }

    public String getReportPathPattern() {
        return state.reportPathPattern;
    }

    public void setReportPathPattern(String pattern) {
        state.reportPathPattern = pattern;
    }
}
