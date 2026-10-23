package se.deversity.asynctest.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code asyncTest { }} block: the options the agent is started with (#1045).
 *
 * <pre>{@code
 * asyncTest {
 *     includes.add("com.example")   // the packages to weave; the agent's includes=
 * }
 * }</pre>
 */
public abstract class AsyncTestExtension {

    /**
     * {@return the package or class name prefixes to weave, the agent's {@code includes=}} Empty, the
     * default, passes no {@code includes=}, and the agent weaves every class it is allowed to, which
     * is slow; name your own packages.
     */
    public abstract ListProperty<String> getIncludes();

    /** {@return whether field accesses are woven, the agent's {@code fields=}; true by default} */
    public abstract Property<Boolean> getFields();

    /** {@return whether collection, lock and blocking calls are woven, the agent's {@code collections=}; true by default} */
    public abstract Property<Boolean> getCollections();

    /**
     * {@return the async-test-agent version the {@code asyncTestAgent} configuration asks for} The
     * plugin's own version by default, which is the library release it was built with.
     */
    public abstract Property<String> getAgentVersion();
}
