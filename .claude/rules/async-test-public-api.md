---
paths: ["**/spi/**", "**/report/**", "**/AsyncAssert.java", "**/AsyncTestListener.java", "**/AsyncTestListenerRegistry.java"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-public-api

## Exclusion Rule

### se.deversity.asynctest.NoopAsyncTestListener
This element is strictly excluded from AI context. Do not reference it.

## Performance Constraints

### se.deversity.asynctest.spi.adapters.LegacyDetectorAdapter
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: analyze() does Method.getMethod + invoke each call; only invoked once per round per detector, not on the hot recordAccess path. If profiling shows reflection overhead, cache the Method handles in the constructor.

## Legacy Compatibility Bridge

### se.deversity.asynctest.spi.adapters.LegacyDetectorAdapter
- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested.

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.

### se.deversity.asynctest.AsyncAssert
- **Reason**: Public assertion utility API for AsyncTest consumers. awaitUntil() and capture() are used directly in user test code — method signatures and semantics must not change without a major version bump.

### se.deversity.asynctest.AsyncTestListener
- **Reason**: Public SPI interface for observing async-test lifecycle events. Method signatures are part of the stable API — implementors bind to these exact names and parameter types.

### se.deversity.asynctest.AsyncTestListenerRegistry
- **Reason**: Public API for registering and unregistering AsyncTestListener instances. register(), unregister(), clearAll(), and fireXxx() methods are called by user code and infrastructure — signatures must not change.

### se.deversity.asynctest.report.Formatter
- **Reason**: Public formatter SPI. format(List<Violation>) signature must not change — built-in formatters and user-provided lambdas bind to this exact type.

### se.deversity.asynctest.spi.Detector
- **Reason**: Public SPI interface. type(), analyze(), onTestStart(), and onTestEnd() signatures are part of the stable extension contract — implementors bind to these exact names and parameter types.

### se.deversity.asynctest.spi.DetectorFactory
- **Reason**: Public SPI interface for ServiceLoader-based detector discovery. type(), isEnabledFor(), and create() signatures are part of the stable factory contract — implementors bind to these exact names and parameter types.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
- **Applies to**: `se.deversity.asynctest.AsyncAssert`, `se.deversity.asynctest.AsyncTestListener`, `se.deversity.asynctest.AsyncTestListenerRegistry`, `se.deversity.asynctest.report.Formatter`, `se.deversity.asynctest.spi.Detector`, `se.deversity.asynctest.spi.DetectorFactory`, `se.deversity.asynctest.report.Violation`, `se.deversity.asynctest.spi.DetectorRegistry`, `se.deversity.asynctest.report.JsonFormatter`, `se.deversity.asynctest.report.MarkdownFormatter`

## Idempotency Guarantee
- **Rule**: These operations are idempotent. Calling them multiple times must produce the same result as calling them once.

### se.deversity.asynctest.AsyncTestListenerRegistry.unregister(se.deversity.asynctest.AsyncTestListener)
- **Reason**: Backed by List.remove which is a no-op when the listener is absent; second call returns false but produces no observable side effect.

### se.deversity.asynctest.AsyncTestListenerRegistry.clearAll()
- **Reason**: List.clear() on an already-empty list is a no-op; repeated calls have identical observable effect (empty registry).

### se.deversity.asynctest.spi.DetectorRegistry.analyzeAll()
- **Reason**: Each Detector.analyze() must return the same violations for the same observed state (the SPI contract). Calling analyzeAll() N times on a quiescent registry yields N identical lists; do not introduce stateful side-effects in analyze().

### se.deversity.asynctest.AsyncTestListenerRegistry.Registration.close()
- **Reason**: Guarded by the `closed` volatile flag; second close() returns early before touching the registry. Covered by `registrationClose_isIdempotent` test.

## Polymorphic Extension Pattern
- **Pattern**: STRATEGY_PATTERN
- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing these files.
- **Applies to**: `se.deversity.asynctest.report.Formatter`, `se.deversity.asynctest.spi.Detector`

## Immutable Type
- **Rule**: These types are immutable. Never introduce non-final fields, setters, or mutating methods.

### se.deversity.asynctest.report.Violation
- **Note**: Java record — fields are final by language. Collection fields are deep-copied to immutable views in the canonical constructor.

### se.deversity.asynctest.spi.DetectorRegistry
- **Note**: Effectively immutable after build() — the EnumMap is populated only in the private constructor and never mutated thereafter; safe to publish to multiple threads and read-only views over an EnumMap populated once at construction.

## Input Sanitization
- **Target Filters**: XSS
- **Rule**: Run raw input strings through approved sanitizers.
- **Applies to**: `se.deversity.asynctest.report.JUnitXmlReportListener.onStructuredReport(java.lang.String,se.deversity.asynctest.diagnostics.IssueSeverity,java.lang.String)#report`, `se.deversity.asynctest.report.JsonReportListener.onStructuredReport(java.lang.String,se.deversity.asynctest.diagnostics.IssueSeverity,java.lang.String)#report`
<!-- VIBETAGS-END -->
