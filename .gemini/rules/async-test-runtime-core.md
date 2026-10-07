<!-- VIBETAGS-START -->
# Rules for async-test-runtime-core

## Locked Status

### se.deversity.asynctest.runner.OfflineLicense.VENDOR_VERIFY_KEY_B64
- **Reason**: Every offline licence file already issued to a customer verifies against this key, and its private half exists only on the operator machine. A changed value denies each of those files with OFFLINE_FILE_SIGNATURE_INVALID on the customer's next build. Rotation means re-issuing every file first: a release decision, not a code edit.

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.

### se.deversity.asynctest.runner.LicenseGuard
- **Aspect**: authorization

### se.deversity.asynctest.runner.LicenseValidationCache
- **Aspect**: authorization (isFresh skips online validation; hasRecord admits outage grace)

### se.deversity.asynctest.runner.OfflineLicense
- **Aspect**: authorization

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

### se.deversity.asynctest.extension.AsyncTestInvocationInterceptor
- **Note**: invocation.skip() is intentional — ConcurrencyRunner owns the full N×M execution and must never call invocation.proceed(). Restoring proceed() would run the test body once outside the CyclicBarrier, bypassing all detectors.

### se.deversity.asynctest.runner.ConcurrencyRunner
- **Note**: Core stress-test execution engine. The CyclicBarrier pattern forces maximum thread contention. Timeout logic and AsyncTestContext install/uninstall are carefully calibrated — subtle changes introduce flaky tests or missed detector activations.

## Thread-Safety Guarantee

### se.deversity.asynctest.AsyncTestContext
- **Strategy**: THREAD_LOCAL
- **Note**: CURRENT ThreadLocal maintains context per active test thread symmetrically.

### se.deversity.asynctest.runner.ConcurrencyRunner
- **Strategy**: OTHER
- **Note**: Coordinates concurrency using CyclicBarrier to maximize thread contention.

### se.deversity.asynctest.runner.LicenseGuard
- **Strategy**: OTHER
- **Note**: ConcurrentHashMap.computeIfAbsent guarantees at-most-once gate execution per fingerprint under contention; volatile announce flags collapse the GRANTED/CI/grace banners to once-per-JVM.

### se.deversity.asynctest.runner.LicenseValidationCache
- **Strategy**: OTHER
- **Note**: Stateless static methods over the filesystem. Concurrent writers race on an atomic temp-file move where the losing write is equivalent to the winning one; readers see either the old complete file or the new complete file, never a partial write. On Windows a read that meets a replace in progress fails with AccessDeniedException, so isFresh retries any read failure but a missing file for up to 127 ms (#928).

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
- **Applies to**: `se.deversity.asynctest.AsyncTestContext`, `se.deversity.asynctest.AsyncTestContext.sharedCryptographyDetector()`, `se.deversity.asynctest.AsyncTestContext.sharedMessageDigestDetector()`, `se.deversity.asynctest.extension.AsyncTestExtension`

## Idempotency Guarantee
- **Rule**: These operations are idempotent. Calling them multiple times must produce the same result as calling them once.

### se.deversity.asynctest.AsyncTestContext.uninstall()
- **Reason**: ThreadLocal.remove() is documented as a no-op when the thread has no value set; the install/uninstall symmetry rule (CLAUDE.md) tolerates extra uninstalls. ConcurrencyRunner relies on this in its outermost-finally cleanup.

### se.deversity.asynctest.runner.LicenseGuard.check(se.deversity.asynctest.AsyncTestConfig)
- **Reason**: ConcurrentHashMap.computeIfAbsent guarantees the underlying gate.check fires at most once per Fingerprint; repeat calls return immediately. Denied results consistently throw SecurityException for the same fingerprint.

## Access Restrictions
- **Allowed Callers**: [se.deversity.asynctest.runner.ConcurrencyRunner]
- **Applies to**: `se.deversity.asynctest.AsyncTestContext.install(se.deversity.asynctest.AsyncTestContext)`, `se.deversity.asynctest.AsyncTestContext.install(se.deversity.asynctest.AsyncTestContext,int)`

## Load-Bearing Oddity
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.

### se.deversity.asynctest.extension.AsyncTestInvocationInterceptor.interceptTestTemplateMethod(org.junit.jupiter.api.extension.InvocationInterceptor.Invocation<java.lang.Void>,org.junit.jupiter.api.extension.ReflectiveInvocationContext<java.lang.reflect.Method>,org.junit.jupiter.api.extension.ExtensionContext)
- **Invariant**: This method calls invocation.skip() and never invocation.proceed().
- **Breaks if changed**: Someone 'fixes' the apparently-dropped invocation by calling proceed(). The test body then runs once on the JUnit thread, outside the CyclicBarrier and outside AsyncTestContext, so no detector observes it — and because that single run usually passes, the suite goes green while every concurrency check has silently stopped running.

### se.deversity.asynctest.runner.ConcurrencyRunner.execute(org.junit.jupiter.api.extension.ReflectiveInvocationContext<java.lang.reflect.Method>,se.deversity.asynctest.AsyncTestConfig)
- **Invariant**: The timeoutAlreadyReported flag, and the per-step guarded cleanup in the finally block, are both deliberate. A pre-round deadline check throws an error that has already been through timeoutError(), and each cleanup step is wrapped in its own try so one failure cannot suppress the next.
- **Breaks if changed**: The flag is removed as redundant — the catch block then sends the same error through timeoutError() a second time, producing two onTimeout callbacks and two copies of every report for one timeout. Or the cleanup steps are merged into one try, at which point a failing AsyncTestContext.uninstall() skips the livelock snapshot and leaks context into the next test.

## PII / Privacy Guardrails

### se.deversity.asynctest.runner.LicenseGuard.Fingerprint
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Holds the licence key and the licensed user's email, and the record's generated toString() prints both, so never log or format a Fingerprint whole. The email may reach the licence provider (Keygen user scope, LemonSqueezy binding) and a denial message in that user's own build; disk sees either value only inside the SHA-256 LicenseValidationCache stores. Never put one in an INFO line, a report, SARIF or JUnit XML: those end up in CI logs and published artifacts.

## Contract-Frozen Signature

### se.deversity.asynctest.extension.AsyncTestExtension
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: JUnit 5 TestTemplateInvocationContextProvider SPI. The two overridden methods (supportsTestTemplate, provideTestTemplateInvocationContexts) must preserve their exact signatures as mandated by JUnit.
<!-- VIBETAGS-END -->
