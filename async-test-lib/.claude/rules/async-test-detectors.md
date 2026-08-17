---
paths: ["**/diagnostics/**"]
---

## Conventions for every detector in this package

These hold for all 142 detectors and are therefore stated once here rather than restated as a
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

### se.deversity.asynctest.diagnostics.CompletableFutureCancellationPropagationDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureCancellationPropagationDetectorTest.java

### se.deversity.asynctest.diagnostics.CompletableFutureCombinatorMisuseDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureCombinatorMisuseDetectorTest.java

### se.deversity.asynctest.diagnostics.CompletableFutureCompletionRaceDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureCompletionRaceDetectorTest.java

### se.deversity.asynctest.diagnostics.CompletableFutureObtrudeDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureObtrudeDetectorTest.java

### se.deversity.asynctest.diagnostics.ConfinedArenaThreadEscapeDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ConfinedArenaThreadEscapeDetectorTest.java

### se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/DaemonThreadHygieneDetectorTest.java

### se.deversity.asynctest.diagnostics.FileChannelPositionRaceDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/FileChannelPositionRaceDetectorTest.java

### se.deversity.asynctest.diagnostics.FinalFieldMutationDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/FinalFieldMutationDetectorTest.java

### se.deversity.asynctest.diagnostics.FlowPublisherConcurrencyDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/FlowPublisherConcurrencyDetectorTest.java

### se.deversity.asynctest.diagnostics.HighContentionAtomicDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/HighContentionAtomicDetectorTest.java

### se.deversity.asynctest.diagnostics.JdbcConnectionSharedDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/JdbcConnectionSharedDetectorTest.java

### se.deversity.asynctest.diagnostics.LambdaLostUpdateDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LambdaLostUpdateDetectorTest.java

### se.deversity.asynctest.diagnostics.LazyConstantMisuseDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LazyConstantMisuseDetectorTest.java

### se.deversity.asynctest.diagnostics.LockUpgradeDeadlockDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LockUpgradeDeadlockDetectorTest.java

### se.deversity.asynctest.diagnostics.NonAtomicConcurrentMapUpdateDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/NonAtomicConcurrentMapUpdateDetectorTest.java

### se.deversity.asynctest.diagnostics.NotifyWithoutMonitorDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/NotifyWithoutMonitorDetectorTest.java

### se.deversity.asynctest.diagnostics.PlatformThreadPerTaskDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/PlatformThreadPerTaskDetectorTest.java

### se.deversity.asynctest.diagnostics.RecordMutableComponentLeakDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/RecordMutableComponentLeakDetectorTest.java

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

### se.deversity.asynctest.diagnostics.SharedMemorySegmentRaceDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedMemorySegmentRaceDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedMessageDigestDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedMessageDigestDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedSecureRandomDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedSecureRandomDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedSplittableRandomDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedSplittableRandomDetectorTest.java

### se.deversity.asynctest.diagnostics.SharedStatefulCryptoDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedStatefulCryptoDetectorTest.java

### se.deversity.asynctest.diagnostics.SpuriousWakeupDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SpuriousWakeupDetectorTest.java

### se.deversity.asynctest.diagnostics.StaticInitDeadlockDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/StaticInitDeadlockDetectorTest.java

### se.deversity.asynctest.diagnostics.ThisEscapeDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThisEscapeDetectorTest.java

### se.deversity.asynctest.diagnostics.ThreadLocalCacheDegradationDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadLocalCacheDegradationDetectorTest.java

### se.deversity.asynctest.diagnostics.ThreadLocalRandomMisuseDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadLocalRandomMisuseDetectorTest.java

### se.deversity.asynctest.diagnostics.TryLockMisuseDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/TryLockMisuseDetectorTest.java

### se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/VarHandleNonAtomicUpdateDetectorTest.java

### se.deversity.asynctest.diagnostics.VirtualThreadMonitorSerializationDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadMonitorSerializationDetectorTest.java

### se.deversity.asynctest.diagnostics.VirtualThreadPoolingDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadPoolingDetectorTest.java

### se.deversity.asynctest.diagnostics.VirtualThreadResourceSaturationDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadResourceSaturationDetectorTest.java

### se.deversity.asynctest.diagnostics.WeakHashMapSharedDetector
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/WeakHashMapSharedDetectorTest.java

## Thread-Safety Guarantee

### se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector
- **Strategy**: OTHER
- **Note**: ThreadLocal tracks active callbacks; ConcurrentHashMap stores violations.

### se.deversity.asynctest.diagnostics.CompletableFutureCancellationPropagationDetector
- **Strategy**: OTHER
- **Note**: One ConcurrentHashMap entry per pipeline; events go on copy-on-write lists and carry an atomically issued sequence number, so ordering never depends on a wall clock.

### se.deversity.asynctest.diagnostics.CompletableFutureCombinatorMisuseDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap keyed on the combined future's identity; constituent and await events are copy-on-write lists carrying atomically issued sequence numbers, so the before/after comparison never depends on a wall clock.

### se.deversity.asynctest.diagnostics.CompletableFutureCompletionRaceDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap keyed on future identity; per-future attempt list is copy-on-write and the sequence counter is atomic, so concurrent recorders never lose an attempt.

### se.deversity.asynctest.diagnostics.CompletableFutureObtrudeDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap stores state per CF instance.

### se.deversity.asynctest.diagnostics.ConfinedArenaThreadEscapeDetector
- **Strategy**: OTHER
- **Note**: Per-segment and per-arena state in ConcurrentHashMap; thread sets are ConcurrentHashMap.newKeySet(); counters are LongAdder. The reflective Method handles are resolved once into immutable statics and are themselves thread-safe.

### se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector
- **Strategy**: OTHER
- **Note**: Per-thread access map is a ConcurrentHashMap; first-registration-wins via putIfAbsent.

### se.deversity.asynctest.diagnostics.FileChannelPositionRaceDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet() and track only implicit-position accessors.

### se.deversity.asynctest.diagnostics.FinalFieldMutationDetector
- **Strategy**: OTHER
- **Note**: Per-field state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.FlowPublisherConcurrencyDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet(); counters are LongAdder / AtomicInteger with a CAS high-water mark.

### se.deversity.asynctest.diagnostics.HighContentionAtomicDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; counters are LongAdder; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.JdbcConnectionSharedDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap-backed JDBC-resource tracking; per-resource State holds ConcurrentHashMap.newKeySet() for accessing threads.

### se.deversity.asynctest.diagnostics.LambdaLostUpdateDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap keyed on lambda identity plus captured name; events are appended to a copy-on-write list. The rule groups by observed pre-value and needs no ordering. holdsLock is sampled on the recording thread at record time, which is the only place it means anything.

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

### se.deversity.asynctest.diagnostics.PlatformThreadPerTaskDetector
- **Strategy**: OTHER
- **Note**: Created threads accumulate in a ConcurrentLinkedQueue; probe bookkeeping in ConcurrentHashMap; counters are AtomicInteger. analyze() reads a moment-in-time snapshot and is safe to call concurrently with recording.

### se.deversity.asynctest.diagnostics.RecordMutableComponentLeakDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap keyed on identity hash; the first-sight fingerprint map is populated once under computeIfAbsent and read-only afterwards; thread sets are ConcurrentHashMap.newKeySet(); tracking is capped by MAX_INSTANCES with a LongAdder drop counter.

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

### se.deversity.asynctest.diagnostics.SharedMemorySegmentRaceDetector
- **Strategy**: OTHER
- **Note**: Per-segment state in ConcurrentHashMap; the access log is a CopyOnWriteArrayList bounded by MAX_TRACKED_ACCESSES with a LongAdder drop counter, so an unbounded test cannot exhaust the heap and the report states how many samples were dropped.

### se.deversity.asynctest.diagnostics.SharedMessageDigestDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedSecureRandomDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with double-check (get-then-computeIfAbsent) hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedSplittableRandomDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SharedStatefulCryptoDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with double-check (get-then-computeIfAbsent) hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.SpuriousWakeupDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap stores state per monitor instance.

### se.deversity.asynctest.diagnostics.StaticInitDeadlockDetector
- **Strategy**: OTHER
- **Note**: Holder and wait maps are ConcurrentHashMap keyed on class name / thread id. The live-thread sample is taken at most once and cached in an AtomicReference so analyze() stays idempotent even though the threads it observes are not.

### se.deversity.asynctest.diagnostics.ThisEscapeDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; escape descriptions and observer-thread sets are ConcurrentHashMap.newKeySet(); the completed flag is volatile.

### se.deversity.asynctest.diagnostics.ThreadLocalCacheDegradationDetector
- **Strategy**: OTHER
- **Note**: One state object per ThreadLocal name in a ConcurrentHashMap; instance identities and thread ids are concurrent key-set views, so counting is idempotent under repeated recording from the same thread - a value read twice adds nothing.

### se.deversity.asynctest.diagnostics.ThreadLocalRandomMisuseDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; misusing-thread sets are ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.TryLockMisuseDetector
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap tracks tryLock attempts, results, and unlock violations.

### se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector
- **Strategy**: OTHER
- **Note**: Per-location state in ConcurrentHashMap keyed on a (handle, receiver) identity record; pending reads are a per-thread ConcurrentHashMap entry; counters are LongAdder and detail lists are CopyOnWriteArrayList bounded by MAX_DETAILS.

### se.deversity.asynctest.diagnostics.VirtualThreadMonitorSerializationDetector
- **Strategy**: OTHER
- **Note**: One state object per monitor identity in a ConcurrentHashMap. Queue depth is an atomic counter and its peak is raised with a CAS retry loop, so a peak observed under contention is never lower than the true peak.

### se.deversity.asynctest.diagnostics.VirtualThreadPoolingDetector
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id map values use AtomicInteger counters and ConcurrentHashMap.newKeySet().

### se.deversity.asynctest.diagnostics.VirtualThreadResourceSaturationDetector
- **Strategy**: OTHER
- **Note**: One state object per resource in a ConcurrentHashMap. Waiting and holding are atomic counters and the peaks are maintained with a CAS retry loop, so a peak observed under contention is never lower than the true peak.

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
