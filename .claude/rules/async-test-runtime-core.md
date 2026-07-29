---
paths: ["**/runner/**", "**/extension/**", "**/AsyncTestContext.java"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-runtime-core

## se.deversity.asynctest.AsyncTestContext

## Security Audit Requirements
When modifying this element, audit for:
- Thread Safety issues

## Core Functionality
- **Sensitivity**: Critical
- **Note**: ThreadLocal install/uninstall must always be symmetric. A leak propagates stale detector state across test invocations and causes false positives or missed detections.

## Thread-Safety Guarantee
- **Strategy**: THREAD_LOCAL
- **Note**: CURRENT ThreadLocal maintains context per active test thread symmetrically.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

### Rules for method sharedMessageDigestDetector
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

### Rules for method sharedCryptographyDetector
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

### Rules for method uninstall
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: ThreadLocal.remove() is documented as a no-op when the thread has no value set; the install/uninstall symmetry rule (CLAUDE.md) tolerates extra uninstalls. ConcurrencyRunner relies on this in its outermost-finally cleanup.

### Rules for method install
- **Allowed Callers**: [se.deversity.asynctest.runner.ConcurrencyRunner]

## se.deversity.asynctest.runner.ConcurrencyRunner

## Security Audit Requirements
When modifying this element, audit for:
- Thread Safety issues
- Resource Leaks

## Core Functionality
- **Sensitivity**: Critical
- **Note**: Core stress-test execution engine. The CyclicBarrier pattern forces maximum thread contention. Timeout logic and AsyncTestContext install/uninstall are carefully calibrated — subtle changes introduce flaky tests or missed detector activations.

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Coordinates concurrency using CyclicBarrier to maximize thread contention.

## se.deversity.asynctest.extension.AsyncTestInvocationInterceptor

## Core Functionality
- **Sensitivity**: Critical
- **Note**: invocation.skip() is intentional — ConcurrencyRunner owns the full N×M execution and must never call invocation.proceed(). Restoring proceed() would run the test body once outside the CyclicBarrier, bypassing all detectors.

## se.deversity.asynctest.extension.AsyncTestExtension

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: JUnit 5 TestTemplateInvocationContextProvider SPI. The two overridden methods (supportsTestTemplate, provideTestTemplateInvocationContexts) must preserve their exact signatures as mandated by JUnit.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## se.deversity.asynctest.runner.LicenseGuard

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap.computeIfAbsent guarantees at-most-once gate execution per fingerprint under contention; volatile announce flags collapse the GRANTED/CI banner to once-per-JVM.

### Rules for method check
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: ConcurrentHashMap.computeIfAbsent guarantees the underlying gate.check fires at most once per Fingerprint; repeat calls return immediately. Denied results consistently throw SecurityException for the same fingerprint.

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: authorization
<!-- VIBETAGS-END -->
