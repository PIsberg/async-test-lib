# Example 118 — Reflective Final-Field Mutation (JEP 500, JDK 26)

**Detector**: `FinalFieldMutationDetector` (`DetectorType.FINAL_FIELD_MUTATION`, also usable standalone)
**JDK feature**: JEP 500 — Warnings About Uses of Deep Reflection to Mutate Final Fields (JDK 26)

## The Problem

JDK 26 runs with `--illegal-final-field-mutation=warn` by default and prints:

> Mutating final fields will be blocked in a future release unless final field mutation is enabled.

A future JDK flips the default to `deny` → `IllegalAccessException`. But the concurrency
problem exists **today, on every JDK version**: the JMM's final-field semantics guarantee
that any thread which sees a reference to an object sees its `final` fields fully
initialized — *without synchronization*. That guarantee only covers writes made in the
constructor. A reflective `Field.set(...)` after construction has no fence:

- other threads may **never observe** the new value (`final` reads can be constant-folded
  by the JIT),
- or observe it arbitrarily late,
- and two reflective writers race with no ordering at all.

## The buggy pattern

```java
Field f = RetryPolicyService.class.getDeclaredField("maxRetries");
f.setAccessible(true);
f.setInt(service, 5);        // ✗ warn on JDK 26 → deny in a future release
                             // ✗ readers have no happens-before edge — stale forever
```

Common offenders: test fixtures injecting mocks into final fields, hand-rolled DI,
configuration overrides, serialization hacks.

## The Fix

```java
// Make it non-final (volatile if it must change), or inject via constructor:
var service = new RetryPolicyService(5);
```

## How to Detect

```java
var d = new FinalFieldMutationDetector();
d.recordMutation("RetryPolicyService.maxRetries", Thread.currentThread());  // flagged (HIGH)
d.recordRead("RetryPolicyService.maxRetries", otherThread);                 // escalates (CRITICAL)
assertTrue(d.analyze().hasIssues());
```

Inside `@AsyncTest` the detector is pipeline-wired: grab it with
`AsyncTestContext.finalFieldMutationDetector()` (exclude with
`excludes = { DetectorType.FINAL_FIELD_MUTATION }`).

See [`RetryPolicyServiceTest`](src/test/java/se/deversity/asynctest/example/RetryPolicyServiceTest.java)
for the clean / reflective-override / racing-reader walkthrough — it performs the real
reflective mutation via
[`RetryPolicyService`](src/main/java/se/deversity/asynctest/example/service/RetryPolicyService.java).

## Running

These detectors ship in the in-progress build. Install the parent artifact to your local
Maven repo first (same workflow as `consumer-fixture`):

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
