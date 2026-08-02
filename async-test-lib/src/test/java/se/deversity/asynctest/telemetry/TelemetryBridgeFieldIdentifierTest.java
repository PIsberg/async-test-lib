package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the accessor-to-field mapping that lets a getter and its setter correlate.
 *
 * <p>The advice identifies an access by {@code declaringType.methodName}, so
 * {@code Account.getBalance} and {@code Account.setBalance} arrive as two identifiers.
 * {@link AtomicityValidator} keys its history by identifier and reports a field that more than
 * one thread both read and wrote — which a getter identifier (reads only) and a setter identifier
 * (writes only) can never satisfy separately. The mapping is what makes that finding reachable
 * from agent data at all, so it is worth pinning rather than leaving to the one end-to-end test.
 */
class TelemetryBridgeFieldIdentifierTest {

    @Test
    @DisplayName("a getter and its setter map to the same field identifier")
    void accessorsForOneFieldShareAnIdentifier() {
        assertEquals("com.example.Account.balance",
                TelemetryBridge.fieldIdentifier("com.example.Account.getBalance"));
        assertEquals("com.example.Account.balance",
                TelemetryBridge.fieldIdentifier("com.example.Account.setBalance"));
        assertEquals("com.example.Account.open",
                TelemetryBridge.fieldIdentifier("com.example.Account.isOpen"));
    }

    @Test
    @DisplayName("identifiers that only look like accessors are left alone")
    void nonAccessorsAreUnchanged() {
        // Byte Buddy's JavaBean matchers can select these too: no-argument, non-void "getter",
        // and "isolate". Folding them would invent a field called "ter" or "olate".
        assertEquals("com.example.Parser.getter",
                TelemetryBridge.fieldIdentifier("com.example.Parser.getter"));
        assertEquals("com.example.Parser.isolate",
                TelemetryBridge.fieldIdentifier("com.example.Parser.isolate"));
        // Bare prefixes have no property after them.
        assertEquals("com.example.Parser.get", TelemetryBridge.fieldIdentifier("com.example.Parser.get"));
        // Identifiers from manual TelemetryRegistry.recordAccess callers are not accessors.
        assertEquals("SharedCounter.value", TelemetryBridge.fieldIdentifier("SharedCounter.value"));
        assertEquals("noDotHere", TelemetryBridge.fieldIdentifier("noDotHere"));
    }

    @Test
    @DisplayName("a field one thread reads and another writes is reported, via its accessors")
    void crossThreadReadWriteThroughAccessorsIsReported() {
        AtomicityValidator validator = new AtomicityValidator();

        // What the agent produces: thread 1 calls the getter, thread 2 calls the setter.
        validator.recordFieldAccess(
                TelemetryBridge.fieldIdentifier("com.example.Account.getBalance"), null, false, 1L);
        validator.recordFieldAccess(
                TelemetryBridge.fieldIdentifier("com.example.Account.setBalance"), null, true, 2L);

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertTrue(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.startsWith("com.example.Account.balance")),
                "A field read on one thread and written on another is the mixed read/write "
                        + "finding this analysis exists for; without the accessor mapping the two "
                        + "accesses land under different identifiers and it never fires. Got: "
                        + report.unsafeFieldAccesses);
    }

    @Test
    @DisplayName("without the mapping the same accesses produce no mixed read/write finding")
    void rawAccessorIdentifiersProduceNoFinding() {
        AtomicityValidator validator = new AtomicityValidator();

        validator.recordFieldAccess("com.example.Account.getBalance", null, false, 1L);
        validator.recordFieldAccess("com.example.Account.setBalance", null, true, 2L);

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertFalse(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.contains("Balance")),
                "This is the behaviour the mapping exists to change: keyed by accessor name, the "
                        + "getter bucket holds only reads and the setter bucket only writes, so "
                        + "the mixed read/write condition cannot be met. If this ever starts "
                        + "finding something, AtomicityValidator's keying changed and the mapping "
                        + "should be revisited.");
    }
}
