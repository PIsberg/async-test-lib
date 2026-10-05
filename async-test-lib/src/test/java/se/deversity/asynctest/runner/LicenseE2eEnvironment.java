package se.deversity.asynctest.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decides whether a real-licence E2E test runs, skips, or fails before it starts.
 *
 * <p>Nothing configured is the only state that skips, and only where {@code ATL_E2E_REQUIRED} is
 * not {@code true}: the {@code license-e2e.yml} workflow sets it, so a secret lost there fails the
 * run instead of reading green. Anything configured but unusable - some variables blank, a file
 * path Java cannot open - fails, because that is a broken setup, not an absent one.
 */
final class LicenseE2eEnvironment {

    static final String REQUIRED = "ATL_E2E_REQUIRED";

    private LicenseE2eEnvironment() {
    }

    /** {@return the value of every name, in order}, or aborts or fails as the class describes. */
    static Map<String, String> require(Map<String, String> env, List<String> names) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : names) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                values.put(name, value);
            }
        }
        if (values.isEmpty() && !"true".equals(env.get(REQUIRED))) {
            assumeTrue(false,
                "operator-machine test: source ~/.config/deversity/e2e-license.env to run it");
        }
        if (values.size() < names.size()) {
            fail("real-licence E2E is configured but incomplete; missing: "
                + names.stream().filter(n -> !values.containsKey(n)).toList()
                + (values.isEmpty() ? " (" + REQUIRED + "=true, so a skip would hide it)" : ""));
        }
        return values;
    }

    /** {@return the file the variable names}, failing when it is set but is not a readable file. */
    static Path requireFile(Map<String, String> env, String name) {
        String value = require(env, List.of(name)).get(name);
        Path file = Path.of(value);
        assertTrue(Files.isRegularFile(file),
            name + "=" + value + " is not a file. On Windows an MSYS path such as /c/Users/... "
                + "does not resolve in Java; use C:/Users/... instead");
        return file;
    }
}
