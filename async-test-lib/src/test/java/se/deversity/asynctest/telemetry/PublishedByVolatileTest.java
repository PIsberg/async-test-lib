package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weaver's record of which plain fields a volatile write publishes.
 *
 * <p>Half of the "write it under a lock, expose it with a volatile write" idiom. The other half is
 * the ordering bit on the read, and only the two together suppress a finding: a field that is
 * published but read without the volatile read in front of it keeps reporting, which is what makes
 * this a rule about the idiom rather than about the field.
 */
class PublishedByVolatileTest {

    @Test
    @DisplayName("a field a volatile write publishes is remembered, and an unrelated one is not")
    void publicationIsRecordedPerField() {
        TelemetryRegistry.publishedByVolatile("com.example.Holder.value");

        assertTrue(TelemetryRegistry.isPublishedByVolatile("com.example.Holder.value"),
                "the weaver saw a volatile write in the same method, after this field's write");
        assertFalse(TelemetryRegistry.isPublishedByVolatile("com.example.Holder.other"),
                "a field no volatile write follows must not inherit the publication of its "
                        + "neighbour: the idiom is per field, not per class");
    }

    @Test
    @DisplayName("recording the same publication twice is harmless")
    void publicationIsIdempotent() {
        TelemetryRegistry.publishedByVolatile("com.example.Repeated.value");
        TelemetryRegistry.publishedByVolatile("com.example.Repeated.value");

        assertTrue(TelemetryRegistry.isPublishedByVolatile("com.example.Repeated.value"),
                "the call sits on a woven path and fires on every execution of the method, so it "
                        + "must stay idempotent rather than accumulate");
    }
}
