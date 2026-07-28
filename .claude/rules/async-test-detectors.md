---
paths: ["**/diagnostics/**"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-detectors

## se.deversity.asynctest.diagnostics.SiteCapture

## Performance Constraints
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: Called from detector recordAccess paths; do not allocate when a site is already captured for a given key.

## se.deversity.asynctest.diagnostics.AtomicNonAtomicUpdateDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/AtomicNonAtomicUpdateDetectorTest.java

## se.deversity.asynctest.diagnostics.BlockingQueueDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/BlockingQueueDetectorTest.java

## se.deversity.asynctest.diagnostics.BoxedPrimitiveLockDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/BoxedPrimitiveLockDetectorTest.java

## se.deversity.asynctest.diagnostics.CacheConcurrencyDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CacheConcurrencyDetectorTest.java

## se.deversity.asynctest.diagnostics.CalendarDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CalendarDetectorTest.java

## se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureBlockingCallbackDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ThreadLocal tracks active callbacks; ConcurrentHashMap stores violations.

## se.deversity.asynctest.diagnostics.CompletableFutureChainDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureChainDetectorTest.java

## se.deversity.asynctest.diagnostics.CompletableFutureCommonPoolBlockingDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureCommonPoolBlockingDetectorTest.java

## se.deversity.asynctest.diagnostics.CompletableFutureCompletionLeakDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureCompletionLeakDetectorTest.java

## se.deversity.asynctest.diagnostics.CompletableFutureExceptionDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureExceptionDetectorTest.java

## se.deversity.asynctest.diagnostics.CompletableFutureObtrudeDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureObtrudeDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap stores state per CF instance.

## se.deversity.asynctest.diagnostics.ConcurrentMapComputeRecursionDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ConcurrentMapComputeRecursionDetectorTest.java

## se.deversity.asynctest.diagnostics.ConcurrentModificationDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ConcurrentModificationDetectorTest.java

## se.deversity.asynctest.diagnostics.ConditionVariableDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ConditionVariableDetectorTest.java

## se.deversity.asynctest.diagnostics.CopyOnWriteCollectionDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CopyOnWriteCollectionDetectorTest.java

## se.deversity.asynctest.diagnostics.CountDownLatchDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CountDownLatchDetectorTest.java

## se.deversity.asynctest.diagnostics.CyclicBarrierDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/CyclicBarrierDetectorTest.java

## se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/DaemonThreadHygieneDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-thread access map is a ConcurrentHashMap; first-registration-wins via putIfAbsent.

## se.deversity.asynctest.diagnostics.DeprecatedThreadApiDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/DeprecatedThreadApiDetectorTest.java

## se.deversity.asynctest.diagnostics.DoubleCheckedLockingDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/DoubleCheckedLockingDetectorTest.java

## se.deversity.asynctest.diagnostics.ExchangerDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ExchangerDetectorTest.java

## se.deversity.asynctest.diagnostics.ExecutorDeadlockDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ExecutorDeadlockDetectorTest.java

## se.deversity.asynctest.diagnostics.ExecutorShutdownDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ExecutorShutdownDetectorTest.java

## se.deversity.asynctest.diagnostics.ExplicitGcDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ExplicitGcDetectorTest.java

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

## se.deversity.asynctest.diagnostics.ForkJoinPoolDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ForkJoinPoolDetectorTest.java

## se.deversity.asynctest.diagnostics.ForkJoinTaskBlockingDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ForkJoinTaskBlockingDetectorTest.java

## se.deversity.asynctest.diagnostics.FutureBlockingDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/FutureBlockingDetectorTest.java

## se.deversity.asynctest.diagnostics.FutureIgnoredDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/FutureIgnoredDetectorTest.java

## se.deversity.asynctest.diagnostics.GathererConcurrencyMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/GathererConcurrencyMisuseDetectorTest.java

## se.deversity.asynctest.diagnostics.HighContentionAtomicDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/HighContentionAtomicDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; counters are LongAdder; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.HttpClientConcurrencyDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/HttpClientConcurrencyDetectorTest.java

## se.deversity.asynctest.diagnostics.InheritableThreadLocalMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/InheritableThreadLocalMisuseDetectorTest.java

## se.deversity.asynctest.diagnostics.InterruptSwallowingDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/InterruptSwallowingDetectorTest.java

## se.deversity.asynctest.diagnostics.JdbcConnectionSharedDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/JdbcConnectionSharedDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap-backed JDBC-resource tracking; per-resource State holds ConcurrentHashMap.newKeySet() for accessing threads.

## se.deversity.asynctest.diagnostics.LatchMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LatchMisuseDetectorTest.java

## se.deversity.asynctest.diagnostics.LazyConstantMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LazyConstantMisuseDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-constant state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id sets are ConcurrentHashMap.newKeySet(); reports are synchronized lists.

## se.deversity.asynctest.diagnostics.LazyInitRaceDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LazyInitRaceDetectorTest.java

## se.deversity.asynctest.diagnostics.LockContentionDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LockContentionDetectorTest.java

## se.deversity.asynctest.diagnostics.LockDowngradeDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LockDowngradeDetectorTest.java

## se.deversity.asynctest.diagnostics.LockLeakDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LockLeakDetectorTest.java

## se.deversity.asynctest.diagnostics.LockUpgradeDeadlockDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/LockUpgradeDeadlockDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap tracks read lock ownership and violations.

## se.deversity.asynctest.diagnostics.MdcContextLeakDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/MdcContextLeakDetectorTest.java

## se.deversity.asynctest.diagnostics.MissedSignalDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/MissedSignalDetectorTest.java

## se.deversity.asynctest.diagnostics.MutableMapKeyDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/MutableMapKeyDetectorTest.java

## se.deversity.asynctest.diagnostics.NestedMonitorLockoutDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/NestedMonitorLockoutDetectorTest.java

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

## se.deversity.asynctest.diagnostics.OptimisticReadValidationDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/OptimisticReadValidationDetectorTest.java

## se.deversity.asynctest.diagnostics.ParallelStreamDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ParallelStreamDetectorTest.java

## se.deversity.asynctest.diagnostics.PhaserDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/PhaserDetectorTest.java

## se.deversity.asynctest.diagnostics.PublicLockExposureDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/PublicLockExposureDetectorTest.java

## se.deversity.asynctest.diagnostics.ReentrantLockDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ReentrantLockDetectorTest.java

## se.deversity.asynctest.diagnostics.ResourceLeakDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ResourceLeakDetectorTest.java

## se.deversity.asynctest.diagnostics.ScheduledExecutorDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ScheduledExecutorDetectorTest.java

## se.deversity.asynctest.diagnostics.ScopedValueMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ScopedValueMisuseDetectorTest.java

## se.deversity.asynctest.diagnostics.SemaphoreMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SemaphoreMisuseDetectorTest.java

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

## se.deversity.asynctest.diagnostics.SharedCollectionDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedCollectionDetectorTest.java

## se.deversity.asynctest.diagnostics.SharedDecimalFormatDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedDecimalFormatDetectorTest.java

## se.deversity.asynctest.diagnostics.SharedDeflaterDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedDeflaterDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.SharedFormatterDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedFormatterDetectorTest.java

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

## se.deversity.asynctest.diagnostics.SharedMatcherDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedMatcherDetectorTest.java

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

## se.deversity.asynctest.diagnostics.SharedRandomDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedRandomDetectorTest.java

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

## se.deversity.asynctest.diagnostics.SharedTimeZoneDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedTimeZoneDetectorTest.java

## se.deversity.asynctest.diagnostics.SharedXmlParserDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SharedXmlParserDetectorTest.java

## se.deversity.asynctest.diagnostics.SimpleDateFormatDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SimpleDateFormatDetectorTest.java

## se.deversity.asynctest.diagnostics.SleepInLockDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SleepInLockDetectorTest.java

## se.deversity.asynctest.diagnostics.SpuriousWakeupDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SpuriousWakeupDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap stores state per monitor instance.

## se.deversity.asynctest.diagnostics.StableValueMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/StableValueMisuseDetectorTest.java

## se.deversity.asynctest.diagnostics.StampedLockDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/StampedLockDetectorTest.java

## se.deversity.asynctest.diagnostics.StatefulLambdaDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/StatefulLambdaDetectorTest.java

## se.deversity.asynctest.diagnostics.StreamClosingDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/StreamClosingDetectorTest.java

## se.deversity.asynctest.diagnostics.StringBuilderDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/StringBuilderDetectorTest.java

## se.deversity.asynctest.diagnostics.StructuredConcurrencyMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/StructuredConcurrencyMisuseDetectorTest.java

## se.deversity.asynctest.diagnostics.StructuredTaskScopeMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/StructuredTaskScopeMisuseDetectorTest.java

## se.deversity.asynctest.diagnostics.SynchronizedCollectionIterationDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SynchronizedCollectionIterationDetectorTest.java

## se.deversity.asynctest.diagnostics.SynchronizedNonFinalDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SynchronizedNonFinalDetectorTest.java

## se.deversity.asynctest.diagnostics.SynchronizedOnLiteralDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SynchronizedOnLiteralDetectorTest.java

## se.deversity.asynctest.diagnostics.SystemPropertyMutationDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/SystemPropertyMutationDetectorTest.java

## se.deversity.asynctest.diagnostics.ThisEscapeDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThisEscapeDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; escape descriptions and observer-thread sets are ConcurrentHashMap.newKeySet(); the completed flag is volatile.

## se.deversity.asynctest.diagnostics.ThreadFactoryDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadFactoryDetectorTest.java

## se.deversity.asynctest.diagnostics.ThreadLeakDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadLeakDetectorTest.java

## se.deversity.asynctest.diagnostics.ThreadLocalContaminationDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadLocalContaminationDetectorTest.java

## se.deversity.asynctest.diagnostics.ThreadLocalRandomMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadLocalRandomMisuseDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; misusing-thread sets are ConcurrentHashMap.newKeySet().

## se.deversity.asynctest.diagnostics.ThreadPoolDeadlockDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadPoolDeadlockDetectorTest.java

## se.deversity.asynctest.diagnostics.ThreadStarvationDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/ThreadStarvationDetectorTest.java

## se.deversity.asynctest.diagnostics.TimerDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/TimerDetectorTest.java

## se.deversity.asynctest.diagnostics.TryLockMisuseDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/TryLockMisuseDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap tracks tryLock attempts, results, and unlock violations.

## se.deversity.asynctest.diagnostics.UnboundedQueueDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/UnboundedQueueDetectorTest.java

## se.deversity.asynctest.diagnostics.UncaughtExceptionHandlerDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/UncaughtExceptionHandlerDetectorTest.java

## se.deversity.asynctest.diagnostics.UncommittedChangesDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/UncommittedChangesDetectorTest.java

## se.deversity.asynctest.diagnostics.VirtualThreadCarrierExhaustionDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadCarrierExhaustionDetectorTest.java

## se.deversity.asynctest.diagnostics.VirtualThreadContextLeakDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadContextLeakDetectorTest.java

## se.deversity.asynctest.diagnostics.VirtualThreadCpuBoundTaskDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadCpuBoundTaskDetectorTest.java

## se.deversity.asynctest.diagnostics.VirtualThreadPinningDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadPinningDetectorTest.java

## se.deversity.asynctest.diagnostics.VolatileArrayDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/VolatileArrayDetectorTest.java

## se.deversity.asynctest.diagnostics.WaitTimeoutDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/WaitTimeoutDetectorTest.java

## se.deversity.asynctest.diagnostics.WeakHashMapSharedDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/WeakHashMapSharedDetectorTest.java

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap-backed instance tracking; per-instance State holds ConcurrentHashMap.newKeySet() for thread ids/names.

## se.deversity.asynctest.diagnostics.WeakReferenceRaceDetector

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 80%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/se/deversity/asynctest/diagnostics/WeakReferenceRaceDetectorTest.java

## se.deversity.asynctest.diagnostics.SiteCapture.Site

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Java record — fields are final by language; types are all primitives or String.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->
