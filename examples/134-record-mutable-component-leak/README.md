# Example 134 — Record Mutable Component Leak

**Detector**: `RecordMutableComponentLeakDetector` (`DetectorType.RECORD_MUTABLE_COMPONENT_LEAK`, also usable standalone)

## The Problem

Records are **shallowly** immutable, and that word does a great deal of quiet damage.

The record's *fields* cannot be reassigned. What they point at can be anything, including an
`ArrayList` that every holder of the record can still mutate:

```java
record Order(String id, List<String> lines) { }

var lines = new ArrayList<>(List.of("widget"));
var order = new Order("o-1", lines);
publish(order);            // handed to three consumer threads
lines.add("late-item");    // they all see it, whenever they happen to look
```

The record is doing exactly what it promised. The mistake is the inference: *"it's a record,
so it's safe to share"* holds only when every component is itself immutable.

Here the caller kept a reference to the same list the record holds, so the snapshot is not a
snapshot. It is a live view that can change under a consumer mid-read — and `ArrayList` offers
no thread safety while that happens, so this is a data race, not merely surprising semantics.

## The Fix

A **compact constructor** — the one place a record gives you to enforce an invariant:

```java
record SafeOrder(String id, List<String> lines) {
    SafeOrder {
        lines = List.copyOf(lines);
    }
}
```

`List.copyOf` does both halves of the job: it copies, so the caller's later `add` cannot reach
the record, and it returns something genuinely unmodifiable, so a consumer cannot mutate it
either. `Map.copyOf` and `Set.copyOf` are the equivalents.

What it costs is one copy per construction. For a snapshot handed to several threads that is a
bargain against a data race. If profiling ever says otherwise, the answer is a persistent
collection — not a shared mutable one.

Arrays deserve a specific warning: a record component of array type can never be made safe by
`copyOf` alone, because the array itself is always mutable. Copy on the way in *and* on the way
out, or use a `List`.

## How to Detect

```java
var d = new RecordMutableComponentLeakDetector();
d.recordShared(order, "order", threadA);
d.recordShared(order, "order", threadB);
lines.add("late-item");                       // the leak
assertTrue(d.analyze().hasIssues());
```

The detector fingerprints each component when the record is shared and compares on `analyze()`.
That is what lets it distinguish two cases which deserve different urgency:

| Situation | Severity | Why |
|---|---|---|
| Component **observed** changing while shared | **HIGH** | a verdict — it happened |
| Component merely mutable and shared, unchanged | lower | a structural risk — the next release of the calling code may well mutate it |
| All components immutable | silent | nothing to leak |

Reporting the unmutated case at all is deliberate. A mutable component reachable from two
threads is a bug waiting for its first commit, and the fix is the same one either way.

Inside `@AsyncTest`, select it with `includes = { DetectorType.RECORD_MUTABLE_COMPONENT_LEAK }`.
Siblings: `THIS_ESCAPE` ([example 107](../107-this-escape/)) and `CONSTRUCTOR_SAFETY`
([example 100](../100-constructor-safety/)) for the other ways a half-built or
half-encapsulated object reaches another thread.

See [`OrderBookTest`](src/test/java/se/deversity/asynctest/example/OrderBookTest.java) for the
copied / observed-mutation / structural-risk / immutable-values walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
