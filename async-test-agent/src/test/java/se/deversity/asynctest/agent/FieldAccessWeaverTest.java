package se.deversity.asynctest.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level guards for the field weaver's two pure decisions: which owners are observed, and
 * what identifier an observed access reports.
 *
 * <p>Both are cheap to get wrong in a way no end-to-end test would attribute correctly. A missing
 * owner exclusion does not fail loudly — it either floods the report with JDK internals or, in the
 * library's own case, recurses through {@code TelemetryRegistry} while it is recording. A wrong
 * identifier format does not fail loudly either: reads and writes for one field land in separate
 * buckets and the finding simply never fires, which looks exactly like correct code.
 */
class FieldAccessWeaverTest {

    @Test
    @DisplayName("the library's own fields are never woven, which is what prevents recursion")
    void skipsOwnPackage() {
        assertFalse(FieldAccessWeaver.shouldWeave(
                        "se/deversity/asynctest/telemetry/TelemetryRegistry"),
                "Weaving a field access inside the telemetry sink would emit an event from inside "
                        + "the code handling an event. This exclusion is the recursion guard.");
    }

    @Test
    @DisplayName("JDK and Byte Buddy owners are skipped so user code does not emit per System.out")
    void skipsPlatformOwners() {
        assertFalse(FieldAccessWeaver.shouldWeave("java/lang/System"));
        assertFalse(FieldAccessWeaver.shouldWeave("jdk/internal/misc/Unsafe"));
        assertFalse(FieldAccessWeaver.shouldWeave("sun/nio/ch/SelectorImpl"));
        assertFalse(FieldAccessWeaver.shouldWeave("com/sun/proxy/Proxy0"));
        assertFalse(FieldAccessWeaver.shouldWeave("net/bytebuddy/agent/Attacher"));
    }

    @Test
    @DisplayName("ordinary application owners are woven")
    void weavesApplicationOwners() {
        assertTrue(FieldAccessWeaver.shouldWeave("com/example/agentfixture/DirectFieldMutationBean"));
        assertTrue(FieldAccessWeaver.shouldWeave("org/springframework/beans/Bean"));
    }

    @Test
    @DisplayName("a near-miss on a skipped prefix is still woven")
    void prefixMatchIsNotOverBroad() {
        // "javax." and "javafoo." both start with "java" but not with the "java/" prefix that is
        // actually excluded; a startsWith check against the dotted form would wrongly skip them.
        assertTrue(FieldAccessWeaver.shouldWeave("javax/inject/Provider"));
        assertTrue(FieldAccessWeaver.shouldWeave("sunset/Beach"));
    }

    @Test
    @DisplayName("the identifier is the dotted owner and field, which the bridge leaves intact")
    void identifierIsDottedOwnerAndField() {
        assertEquals("com.example.agentfixture.DirectFieldMutationBean.count",
                FieldAccessWeaver.identifier(
                        "com/example/agentfixture/DirectFieldMutationBean", "count"),
                "TelemetryBridge.fieldIdentifier strips a get/set/is prefix from the last segment "
                        + "and otherwise returns the identifier unchanged. A plain field name is "
                        + "therefore passed through, which is what puts a direct access and a "
                        + "woven accessor for the same field under one key.");
    }
}
