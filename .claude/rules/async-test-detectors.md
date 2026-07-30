---
paths: ["**/diagnostics/**"]
---

## Conventions for every detector in this package

These hold for all ~110 detectors and are therefore stated once here rather than restated as a
per-class `@AITestDriven` annotation — one annotation per detector cost one `<element>` line each
in the always-loaded `<scoped_rules>` index of `CLAUDE.md`.

- **Tests are mandatory.** A change to `XDetector.java` requires a matching change to
  `src/test/java/se/deversity/asynctest/diagnostics/XDetectorTest.java`. JUnit 5, 80% coverage goal.
- **Thread-safe by construction.** Detector state is shared across the N×M worker threads. Use
  `ConcurrentHashMap` / `ConcurrentHashMap.newKeySet()` / `LongAdder`; never a bare `HashMap` or a
  non-final lock target.
- **Allocation-free on the record path.** `recordX(...)` runs inside the contended region. Use
  get-then-`computeIfAbsent`, and do not allocate when state already exists for a key.

Detectors that carry additional guarantees (an explicit thread-safety strategy, a security aspect,
a performance budget) are annotated individually and appear below.

<!-- VIBETAGS-START -->
# Rules for async-test-detectors

## se.deversity.asynctest.diagnostics.SiteCapture

## Performance Constraints
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: Called from detector recordAccess paths; do not allocate when a site is already captured for a given key.

## se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureBlockingCallbackDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ThreadLocal tracks active callbacks; ConcurrentHashMap stores violations.

## se.deversity.asynctest.diagnostics.CompletableFutureObtrudeDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureObtrudeDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap stores state per CF instance.

## se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/DaemonThreadHygieneDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-thread access map is a ConcurrentHashMap; first-registration-wins via putIfAbsent.

## se.deversity.asynctest.diagnostics.FileChannelPositionRaceDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/FileChannelPositionRaceDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet() and track only implicit-position accessors.

## se.deversity.asynctest.diagnostics.FinalFieldMutationDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/FinalFieldMutationDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-field state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.HighContentionAtomicDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/HighContentionAtomicDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; counters are LongAdder; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.JdbcConnectionSharedDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/JdbcConnectionSharedDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap-backed JDBC-resource tracking; per-resource State holds ConcurrentHashMap.newKeySet() for accessing threads.

## se.deversity.asynctest.diagnostics.LazyConstantMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LazyConstantMisuseDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-constant state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id sets are ConcurrentHashMap.newKeySet(); reports are synchronized lists.

## se.deversity.asynctest.diagnostics.LockUpgradeDeadlockDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LockUpgradeDeadlockDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap tracks read lock ownership and violations.

## se.deversity.asynctest.diagnostics.NonAtomicConcurrentMapUpdateDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/NonAtomicConcurrentMapUpdateDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per (map,key) state in a ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.NotifyWithoutMonitorDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/NotifyWithoutMonitorDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: Attempts list mutated under a single intrinsic monitor on the list itself; sampling Thread.holdsLock requires no locking.

## se.deversity.asynctest.diagnostics.SharedByteBufferDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedByteBufferDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name and operation sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.SharedCharsetCoderDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedCharsetCoderDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.SharedChecksumDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedChecksumDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.SharedDeflaterDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedDeflaterDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.SharedIteratorDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedIteratorDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.SharedJsonMapperReconfigDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedJsonMapperReconfigDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; using-thread sets are ConcurrentHashMap.newKeySet(); violating mutations recorded in a CopyOnWriteArrayList.

## se.deversity.asynctest.diagnostics.SharedKdfDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedKdfDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.SharedMessageDigestDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedMessageDigestDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: cryptography (hash integrity / MAC / signature state)

## se.deversity.asynctest.diagnostics.SharedSecureRandomDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedSecureRandomDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with double-check (get-then-computeIfAbsent) hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: cryptography (RNG quality)

## se.deversity.asynctest.diagnostics.SharedStatefulCryptoDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedStatefulCryptoDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with double-check (get-then-computeIfAbsent) hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: cryptography (confidentiality / integrity / authenticity state)

## se.deversity.asynctest.diagnostics.SpuriousWakeupDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SpuriousWakeupDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap stores state per monitor instance.

## se.deversity.asynctest.diagnostics.ThisEscapeDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThisEscapeDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; escape descriptions and observer-thread sets are ConcurrentHashMap.newKeySet(); the completed flag is volatile.

## se.deversity.asynctest.diagnostics.ThreadLocalRandomMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadLocalRandomMisuseDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; misusing-thread sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.TryLockMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/TryLockMisuseDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap tracks tryLock attempts, results, and unlock violations.

## se.deversity.asynctest.diagnostics.WeakHashMapSharedDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/WeakHashMapSharedDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap-backed instance tracking; per-instance State holds ConcurrentHashMap.newKeySet() for thread ids/names.

## se.deversity.asynctest.diagnostics.SiteCapture.Site

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Java record — fields are final by language; types are all primitives or String.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->
