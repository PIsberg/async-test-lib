package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library these numbers describe is the library in the working tree.
 *
 * <p>This module resolves {@code async-test-lib} from the local repository rather than from the
 * reactor, because it deliberately sits outside the reactor - the corpus measures the library a
 * user would get, not the one the build happens to be holding. The cost of that is a failure mode
 * with no symptom: change a detector, forget {@code mvn install}, and every number in every
 * report comes from the previous build while the source says otherwise.
 *
 * <p>It has happened. Wave 7 opened with a row that had been green for four waves failing for no
 * reason anyone could see, and the cause was an {@code async-test-lib:1.11.0} jar in {@code ~/.m2}
 * built before a detector fix - same version, different bytes. That was caught only because a
 * pinned pair happened to cover the changed detector. A change to a detector with no pair would
 * have moved the headline rates with nothing going red (#425).
 *
 * <p>Reported as unchecked rather than as passed when there is no working tree to compare
 * against, which is what running the corpus against a released artifact looks like.
 */
class ResolvedLibraryIsCurrentTest {

    @Test
    @DisplayName("the resolved library is not older than the working tree's classes")
    void theResolvedLibraryIsNotStale() {
        Optional<String> complaint = LibraryBuild.stalenessComplaint();
        assertTrue(complaint.isEmpty(), complaint.orElse(""));
    }

    @Test
    @DisplayName("every report names the build it measured")
    void everyReportNamesItsBuild() {
        String description = LibraryBuild.describe();
        assertTrue(description.startsWith("- Library under test: "), description);
        assertTrue(!description.contains("could not be located"),
                "the report header has to name the artifact these numbers came from, or a "
                        + "published number is traceable to a version string and nothing else: "
                        + description);
    }
}
