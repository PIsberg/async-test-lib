package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;

/**
 * Phase 9, repository &amp; environment-state group — {@code UNCOMMITTED_CHANGES}.
 *
 * <p>This detector inspects the working tree rather than the running code, so the fixture
 * asserts reachability only: there is no in-test workload that would make its finding
 * deterministic, and a fixture that shelled out to git would be testing the CI checkout
 * rather than the library.
 *
 * <p>Corresponding example: {@code examples/09-uncommitted-changes-detection}.
 */
class Phase09EnvironmentDetectorsFixtureTest {

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.UNCOMMITTED_CHANGES})
    void uncommittedChanges() {
        reachable("uncommittedChangesDetector()", AsyncTestContext::uncommittedChangesDetector);
    }
}
