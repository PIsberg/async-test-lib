# Shared Non-Thread-Safe JDK Types Example

This example demonstrates **three silent concurrency bugs** that appear in one production
service: sharing a `Matcher`, a `DecimalFormat`, and a `MessageDigest` across threads.

## The Problem

`DataProcessingService` processes payment records with three operations:

| Operation | Shared object | Thread-safe? |
|-----------|--------------|-------------|
| Validate transaction ID format | `Matcher` | ❌ — holds mutable per-match state |
| Format monetary amount | `DecimalFormat` | ❌ — mutates internal digit buffer |
| Compute SHA-256 fingerprint | `MessageDigest` | ❌ — mutates running hash buffer |

All three are classic mistakes: the **read-only** companion (`Pattern`, `NumberFormat`
subclass interface, hash algorithm name) is fine to share, but the **stateful worker
object** must not be.

## Why Sequential Tests Miss These Bugs

```java
@Test
void testValidateAndFormat_Sequential() {
    // One thread, one at a time — shared state is never contested
    assertTrue(service.validateTransactionId("TX-123456-USD"));
    assertEquals("1,234.56", service.formatAmount(1234.56));
    assertEquals(64, service.fingerprint("TX-123456-USD").length());
    // ✅ Passes — but gives FALSE CONFIDENCE
}
```

Each call serialises naturally: `reset()` finishes before the next `reset()` starts,
`format()` finishes before the next, and so on. There are no races to observe.

## How `@AsyncTest` Exposes the Bugs

```java
@AsyncTest(
    threads = 8,
    invocations = 20,
    detectSharedMatcher       = true,
    detectSharedDecimalFormat = true,
    detectSharedMessageDigest = true
)
void testValidateAndFormat_Concurrent() {
    var d1 = AsyncTestContext.sharedMatcherDetector();
    var d2 = AsyncTestContext.sharedDecimalFormatDetector();
    var d3 = AsyncTestContext.sharedMessageDigestDetector();

    d1.recordAccess(/* buggy matcher */, "txIdMatcher", Thread.currentThread());
    service.validateTransactionId("TX-123456-USD");

    d2.recordAccess(/* buggy format  */, "amountFormat", Thread.currentThread());
    service.formatAmount(1234.56);

    d3.recordAccess(/* buggy digest  */, "sha256", Thread.currentThread());
    service.fingerprint("TX-123456-USD");
}
```

With 8 threads colliding on every invocation the detectors report:

```
SHARED REGEX MATCHER DETECTED:
  - 'txIdMatcher' accessed from 8 threads — Matcher is not thread-safe

SHARED DECIMAL FORMAT / NUMBER FORMAT DETECTED:
  - 'amountFormat' accessed from 8 threads — DecimalFormat/NumberFormat is not thread-safe

SHARED MESSAGE DIGEST DETECTED:
  - 'sha256' accessed from 8 threads — MessageDigest is not thread-safe; concurrent
    unsynchronized concurrent update()/digest() calls corrupt the hash state
```

## Running the Example

```bash
cd examples/10-shared-non-thread-safe-types
mvn clean test
# ✅ Tests pass — @Test gives false confidence

# To see the detectors fire, change the @AsyncTest tests in
# SharedNonThreadSafeTypesTest.java from @Test to @AsyncTest
```

## The Fixes

| Bug | Fix |
|-----|-----|
| Shared `Matcher` | `Pattern.matcher(input)` inside each call — `Pattern` itself is safe to share |
| Shared `DecimalFormat` | `ThreadLocal<DecimalFormat>` or `new DecimalFormat(...)` per call |
| Shared `MessageDigest` | `MessageDigest.getInstance("SHA-256")` per call or `ThreadLocal<MessageDigest>` |

The fixed methods (`validateTransactionIdFixed`, `formatAmountFixed`, `fingerprintFixed`)
are included in `DataProcessingService` for direct comparison.

## Severity

| Detector | Failure mode | Symptom |
|----------|-------------|---------|
| `SharedMatcherDetector` | Wrong validation results | Valid IDs rejected; invalid IDs accepted — **silent data loss** |
| `SharedDecimalFormatDetector` | Garbled output / exception | Currency totals wrong on invoices — **financial bug** |
| `SharedMessageDigestDetector` | Wrong hash values | Audit fingerprints differ across runs — **integrity violation**, extremely hard to diagnose |
