package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins when the real-licence E2E tests run, skip, or fail before they start.
 *
 * <p>Both classes skipped on every CI leg from 1.9.1 on, and the offline one also skipped on the
 * Windows operator machine it was written for: the env file held an MSYS path, Java could not
 * open it, and "file missing" was treated as "not the operator machine". A skip is only right
 * when nothing was configured; anything configured but unusable is a failure.
 */
class LicenseE2eEnvironmentTest {

    private static final List<String> NAMES = List.of("ATL_E2E_A", "ATL_E2E_B");

    @TempDir
    Path dir;

    @Test
    void nothingConfiguredSkips() {
        assertThrows(TestAbortedException.class,
            () -> LicenseE2eEnvironment.require(Map.of(), NAMES));
    }

    @Test
    void nothingConfiguredFailsWhereTheRunIsRequired() {
        // The licence-e2e workflow sets ATL_E2E_REQUIRED; a lost secret there must not read green.
        AssertionFailedError e = assertThrows(AssertionFailedError.class,
            () -> LicenseE2eEnvironment.require(Map.of("ATL_E2E_REQUIRED", "true"), NAMES));
        assertTrue(e.getMessage().contains("ATL_E2E_A"), e.getMessage());
    }

    @Test
    void aPartialConfigurationFailsAndNamesWhatIsMissing() {
        AssertionFailedError e = assertThrows(AssertionFailedError.class,
            () -> LicenseE2eEnvironment.require(Map.of("ATL_E2E_A", "x", "ATL_E2E_B", " "), NAMES));
        assertTrue(e.getMessage().contains("ATL_E2E_B"), e.getMessage());
    }

    @Test
    void aFullConfigurationReturnsTheValues() {
        assertEquals(Map.of("ATL_E2E_A", "a", "ATL_E2E_B", "b"),
            LicenseE2eEnvironment.require(Map.of("ATL_E2E_A", "a", "ATL_E2E_B", "b"), NAMES));
    }

    @Test
    void aFileVariablePointingNowhereFails() {
        AssertionFailedError e = assertThrows(AssertionFailedError.class,
            () -> LicenseE2eEnvironment.requireFile(
                Map.of("ATL_E2E_FILE", "/c/Users/nobody/missing.atl-license"), "ATL_E2E_FILE"));
        assertTrue(e.getMessage().contains("/c/Users/nobody/missing.atl-license"), e.getMessage());
    }

    @Test
    void aFileVariablePointingAtAFileReturnsIt() throws IOException {
        Path file = Files.writeString(dir.resolve("real.atl-license"), "payload");
        assertEquals(file, LicenseE2eEnvironment.requireFile(
            Map.of("ATL_E2E_FILE", file.toString()), "ATL_E2E_FILE"));
    }
}
