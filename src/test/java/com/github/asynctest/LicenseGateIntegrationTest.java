package com.github.asynctest;

import org.junit.jupiter.api.Assertions;

public class LicenseGateIntegrationTest {

    @AsyncTest(
        threads = 2, 
        invocations = 10,
        keygenAccountId = "test-acc",
        keygenProductId = "test-prod",
        lemonSqueezyStore = "test-store",
        licenseKey = "ABC-123",
        licenseMockMode = true
    )
    void smokeTestWithLicenseConfiguration() {
        // This test uses the new license configuration attributes.
        // It should log "LICENSE GRANTED" or "LICENSE DENIED" depending on the defaults.
        // Since we are using an enterprise email "user@example.com" hardcoded in the runner,
        // and its classification is COMMERCIAL, it will try to check Keygen and likely fail (Denied).
        Assertions.assertTrue(true);
    }
}
