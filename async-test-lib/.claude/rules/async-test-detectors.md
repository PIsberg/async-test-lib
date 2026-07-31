---
paths: ["**/diagnostics/**"]
---

## Conventions for every detector in this package

These hold for all 127 detectors and are therefore stated once here rather than restated as a
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

## Performance Constraints

### se.deversity.asynctest.diagnostics.SiteCapture
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: Called from detector recordAccess paths; do not allocate when a site is already captured for a given key.

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5

### se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureBlockingCallbackDetectorTest.java

### se.deversity.asynctest.diagnostics.CompletableFutureObtrudeDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureObtrudeDetectorTest.java

### se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/DaemonThreadHygieneDetectorTest.java

### se.deversity.asynctest.diagnostics.FileChannelPositionRaceDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/FileChannelPositionRaceDetectorTest.java

### se.deversity.asynctest.diagnostics.FinalFieldMutationDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/FinalFieldMutationDetectorTest.java

### se.deversity.asynctest.diagnostics.HighContentionAtomicDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/HighContentionAtomicDetectorTest.java

### se.deversity.asynctest.diagnostics.JdbcConnectionSharedDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/JdbcConnectionSharedDetectorTest.java

### se.deversity.asynctest.diagnostics.LazyConstantMisuseDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LazyConstantMisuseDetectorTest.java

### se.deversity.asynctest.diagnostics.LockUpgradeDeadlockDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LockUpgradeDeadlockDetectorTest.java

### se.deversity.asynctest.diagnostics.NonAtomicConcurrentMapUpdateDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/NonAtomicConcurrentMapUpdateDetectorTest.java

### se.deversity.asynctest.diagnostics.NotifyWithoutMonitorDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/NotifyWithoutMonitorDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedByteBufferDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedByteBufferDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedCharsetCoderDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedCharsetCoderDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedChecksumDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedChecksumDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedDeflaterDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedDeflaterDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedIteratorDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedIteratorDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedJsonMapperReconfigDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedJsonMapperReconfigDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedKdfDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedKdfDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedMessageDigestDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedMessageDigestDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedSecureRandomDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedSecureRandomDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedStatefulCryptoDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedStatefulCryptoDetectorTest.java

### se.deversity.asynctest.diagnostics.SpuriousWakeupDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SpuriousWakeupDetectorTest.java

### se.deversity.asynctest.diagnostics.ThisEscapeDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThisEscapeDetectorTest.java

### se.deversity.asynctest.diagnostics.ThreadLocalRandomMisuseDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadLocalRandomMisuseDetectorTest.java

### se.deversity.asynctest.diagnostics.TryLockMisuseDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/TryLockMisuseDetectorTest.java

### se.deversity.asynctest.diagnostics.WeakHashMapSharedDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/WeakHashMapSharedDetectorTest.java

## Thread-Safety Guarantee

### se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector
- **Strategy**: OTHER
- **Note**: ThreadLocal tracks active callbacks; ConcurrentHashMap stores violations.

### se.deversity.asynctest.diagnostics.CompletableFutureObtrudeDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap stores state per CF instance.

### se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector
- **Strategy**: OTHER
- **Note**: Per-thread access map is a ConcurrentHashMap; first-registration-wins via putIfAbsent.

### se.deversity.asynctest.diagnostics.FileChannelPositionRaceDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet() and track only implicit-position accessors.

### se.deversity.asynctest.diagnostics.FinalFieldMutationDetector
- **Strategy**: OTHER
- **Note**: Per-field state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.HighContentionAtomicDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; counters are LongAdder; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.JdbcConnectionSharedDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap-backed JDBC-resource tracking; per-resource State holds ConcurrentHashMap.newKeySet() for accessing threads.

### se.deversity.asynctest.diagnostics.LazyConstantMisuseDetector
- **Strategy**: OTHER
- **Note**: Per-constant state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id sets are ConcurrentHashMap.newKeySet(); reports are synchronized lists.

### se.deversity.asynctest.diagnostics.LockUpgradeDeadlockDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap tracks read lock ownership and violations.

### se.deversity.asynctest.diagnostics.NonAtomicConcurrentMapUpdateDetector
- **Strategy**: OTHER
- **Note**: Per (map,key) state in a ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.NotifyWithoutMonitorDetector
- **Strategy**: SYNCHRONIZED
- **Note**: Attempts list mutated under a single intrinsic monitor on the list itself; sampling Thread.holdsLock requires no locking.

### se.deversity.asynctest.diagnostics.SharedByteBufferDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name and operation sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedCharsetCoderDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedChecksumDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedDeflaterDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedIteratorDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedJsonMapperReconfigDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; using-thread sets are ConcurrentHashMap.newKeySet(); violating mutations recorded in a CopyOnWriteArrayList.

### se.deversity.asynctest.diagnostics.SharedKdfDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedMessageDigestDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedSecureRandomDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with double-check (get-then-computeIfAbsent) hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedStatefulCryptoDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with double-check (get-then-computeIfAbsent) hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SpuriousWakeupDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap stores state per monitor instance.

### se.deversity.asynctest.diagnostics.ThisEscapeDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; escape descriptions and observer-thread sets are ConcurrentHashMap.newKeySet(); the completed flag is volatile.

### se.deversity.asynctest.diagnostics.ThreadLocalRandomMisuseDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; misusing-thread sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.TryLockMisuseDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap tracks tryLock attempts, results, and unlock violations.

### se.deversity.asynctest.diagnostics.WeakHashMapSharedDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap-backed instance tracking; per-instance State holds ConcurrentHashMap.newKeySet() for thread ids/names.

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.

### se.deversity.asynctest.diagnostics.SharedMessageDigestDetector
- **Aspect**: cryptography (hash integrity / MAC / signature state)

### se.deversity.asynctest.diagnostics.SharedSecureRandomDetector
- **Aspect**: cryptography (RNG quality)

### se.deversity.asynctest.diagnostics.SharedStatefulCryptoDetector
- **Aspect**: cryptography (confidentiality / integrity / authenticity state)

## Immutable Type

### se.deversity.asynctest.diagnostics.SiteCapture.Site
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Java record — fields are final by language; types are all primitives or String.

## Public API Surface Protection

### se.deversity.asynctest.diagnostics.SiteCapture.Site
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->
