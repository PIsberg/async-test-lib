---
paths: ["**/runner/**", "**/extension/**", "**/AsyncTestContext.java"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-runtime-core

## Security Audit Requirements
When modifying these elements, audit for:
- Thread Safety issues
- **Applies to**: `se.deversity.asynctest.AsyncTestContext`

### se.deversity.asynctest.runner.ConcurrencyRunner
- Resource Leaks

## Core Functionality
- **Sensitivity**: Critical

### se.deversity.asynctest.AsyncTestContext
- **Note**: ThreadLocal install/uninstall must always be symmetric. A leak propagates stale detector state across test invocations and causes false positives or missed detections.

### se.deversity.asynctest.runner.ConcurrencyRunner
- **Note**: Core stress-test execution engine. The CyclicBarrier pattern forces maximum thread contention. Timeout logic and AsyncTestContext install/uninstall are carefully calibrated — subtle changes introduce flaky tests or missed detector activations.

### se.deversity.asynctest.extension.AsyncTestInvocationInterceptor
- **Note**: invocation.skip() is intentional — ConcurrencyRunner owns the full N×M execution and must never call invocation.proceed(). Restoring proceed() would run the test body once outside the CyclicBarrier, bypassing all detectors.

## Thread-Safety Guarantee

### se.deversity.asynctest.AsyncTestContext
- **Strategy**: THREAD_LOCAL
- **Note**: CURRENT ThreadLocal maintains context per active test thread symmetrically.

### se.deversity.asynctest.runner.ConcurrencyRunner
- **Strategy**: OTHER
- **Note**: Coordinates concurrency using CyclicBarrier to maximize thread contention.

### se.deversity.asynctest.runner.LicenseGuard
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap.computeIfAbsent guarantees at-most-once gate execution per fingerprint under contention; volatile announce flags collapse the GRANTED/CI banner to once-per-JVM.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
- **Applies to**: `se.deversity.asynctest.AsyncTestContext`, `se.deversity.asynctest.AsyncTestContext.sharedMessageDigestDetector()`, `se.deversity.asynctest.AsyncTestContext.sharedCryptographyDetector()`, `se.deversity.asynctest.extension.AsyncTestExtension`

## Idempotency Guarantee
- **Rule**: These operations are idempotent. Calling them multiple times must produce the same result as calling them once.

### se.deversity.asynctest.AsyncTestContext.uninstall()
- **Reason**: ThreadLocal.remove() is documented as a no-op when the thread has no value set; the install/uninstall symmetry rule (CLAUDE.md) tolerates extra uninstalls. ConcurrencyRunner relies on this in its outermost-finally cleanup.

### se.deversity.asynctest.runner.LicenseGuard.check(se.deversity.asynctest.AsyncTestConfig)
- **Reason**: ConcurrentHashMap.computeIfAbsent guarantees the underlying gate.check fires at most once per Fingerprint; repeat calls return immediately. Denied results consistently throw SecurityException for the same fingerprint.

## Access Restrictions

### se.deversity.asynctest.AsyncTestContext.install(se.deversity.asynctest.AsyncTestContext)
- **Allowed Callers**: [se.deversity.asynctest.runner.ConcurrencyRunner]

## Contract-Frozen Signature

### se.deversity.asynctest.extension.AsyncTestExtension
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: JUnit 5 TestTemplateInvocationContextProvider SPI. The two overridden methods (supportsTestTemplate, provideTestTemplateInvocationContexts) must preserve their exact signatures as mandated by JUnit.

## Security-Critical Code

### se.deversity.asynctest.runner.LicenseGuard
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: authorization
<!-- VIBETAGS-END -->
