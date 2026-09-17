package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorFeed;
import se.deversity.asynctest.diagnostics.DetectorTrust;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the exposure mapping in {@link DetectorExposure}.
 */
class DetectorExposureTest {

    @Test
    @DisplayName("every detector class resolves in both directions")
    void everyDetectorClassResolvesBiDirectionally() {
        for (DetectorType type : DetectorType.values()) {
            String className = DetectorExposure.classOf(type);
            assertNotNull(className, "detector class must not be null for " + type);
            assertFalse(className.isBlank(), "detector class must not be blank for " + type);

            Optional<DetectorType> resolved = DetectorExposure.typeOf(className);
            assertTrue(resolved.isPresent(), "detector class " + className + " must resolve back to DetectorType");
            assertEquals(type, resolved.get(), "round-trip resolution mismatch for " + type);
        }
    }

    @Test
    @DisplayName("ZERO_CONFIG detectors are exposed across every lane")
    void zeroConfigDetectorsAreAlwaysExposed() {
        Set<DetectorType> zeroConfig = DetectorExposure.fedBy(DetectorFeed.ZERO_CONFIG);
        assertFalse(zeroConfig.isEmpty(), "zero-config set must not be empty");

        for (CorpusLane lane : CorpusLane.values()) {
            for (DetectorType type : zeroConfig) {
                assertTrue(DetectorExposure.isExposed(type, lane),
                        type + " must be exposed in " + lane.propertyValue());
            }
        }
    }

    @Test
    @DisplayName("agent-fed detectors are never exposed in the agent-off control lane")
    void agentFedDetectorsAreUnexposedInControlLane() {
        Set<DetectorType> agentFed = DetectorExposure.fedBy(DetectorFeed.AGENT);
        for (DetectorType type : agentFed) {
            assertFalse(DetectorExposure.isExposed(type, CorpusLane.AGENT_OFF),
                    "control lane must not expose agent-fed detector: " + type);
        }
    }

    @Test
    @DisplayName("agent-fed detectors are exposed in agent-on and agent-pair lanes")
    void agentFedDetectorsAreExposedInAgentLanes() {
        Set<DetectorType> agentFed = DetectorExposure.fedBy(DetectorFeed.AGENT);
        for (CorpusLane lane : List.of(
                CorpusLane.AGENT_ON,
                CorpusLane.AGENT_PAIRS,
                CorpusLane.AGENT_PAIRS_LIBRARY_EXCLUDED)) {
            for (DetectorType type : agentFed) {
                assertTrue(DetectorExposure.isExposed(type, lane),
                        type + " must be exposed in " + lane.propertyValue());
            }
        }
    }

    @Test
    @DisplayName("recording-fed detectors are unexposed in unmodified lanes")
    void recordingFedDetectorsAreUnexposedInUnmodifiedLanes() {
        Set<DetectorType> recordingFed = DetectorExposure.fedBy(DetectorFeed.RECORDING);
        for (CorpusLane lane : List.of(
                CorpusLane.AGENT_ON,
                CorpusLane.AGENT_OFF,
                CorpusLane.AGENT_PAIRS,
                CorpusLane.AGENT_PAIRS_LIBRARY_EXCLUDED)) {
            for (DetectorType type : recordingFed) {
                assertFalse(DetectorExposure.isExposed(type, lane),
                        "recording detector " + type + " must not be exposed in " + lane.propertyValue());
            }
        }
    }

    @Test
    @DisplayName("exposed(lane) matches isExposed for all detectors in that lane")
    void exposedMatchesIsExposedPredicate() {
        for (CorpusLane lane : CorpusLane.values()) {
            Set<DetectorType> exposedSet = DetectorExposure.exposed(lane);
            for (DetectorType type : DetectorType.values()) {
                boolean predicate = DetectorExposure.isExposed(type, lane);
                assertEquals(predicate, exposedSet.contains(type),
                        "mismatch between exposed() and isExposed() for " + type + " in " + lane.propertyValue());
            }
        }
    }

    @Test
    @DisplayName("all() returns all detector types without omission")
    void allReturnsAllDetectors() {
        assertEquals(EnumSet.allOf(DetectorType.class), DetectorExposure.all());
        assertEquals(DetectorTrust.DETECTOR_COUNT, DetectorExposure.all().size());
    }
}
