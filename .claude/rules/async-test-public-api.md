---
paths: ["**/spi/**", "**/report/**", "**/AsyncAssert.java", "**/AsyncTestListener.java", "**/AsyncTestListenerRegistry.java"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-public-api

## se.deversity.asynctest.NoopAsyncTestListener

## Exclusion Rule
This element is strictly excluded from AI context. Do not reference it.

## se.deversity.asynctest.spi.adapters.LegacyDetectorAdapter

## Performance Constraints
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: analyze() does Method.getMethod + invoke each call; only invoked once per round per detector, not on the hot recordAccess path. If profiling shows reflection overhead, cache the Method handles in the constructor.

## Legacy Compatibility Bridge
- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested.

## se.deversity.asynctest.AsyncAssert

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public assertion utility API for AsyncTest consumers. awaitUntil() and capture() are used directly in user test code — method signatures and semantics must not change without a major version bump.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## se.deversity.asynctest.AsyncTestListener

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public SPI interface for observing async-test lifecycle events. Method signatures are part of the stable API — implementors bind to these exact names and parameter types.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## se.deversity.asynctest.AsyncTestListenerRegistry

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public API for registering and unregistering AsyncTestListener instances. register(), unregister(), clearAll(), and fireXxx() methods are called by user code and infrastructure — signatures must not change.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

### Rules for method unregister
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: Backed by List.remove which is a no-op when the listener is absent; second call returns false but produces no observable side effect.

### Rules for method clearAll
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: List.clear() on an already-empty list is a no-op; repeated calls have identical observable effect (empty registry).

## se.deversity.asynctest.report.Formatter

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public formatter SPI. format(List<Violation>) signature must not change — built-in formatters and user-provided lambdas bind to this exact type.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## Polymorphic Extension Pattern
- **Pattern**: STRATEGY_PATTERN
- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing this file.

## se.deversity.asynctest.spi.Detector

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public SPI interface. type(), analyze(), onTestStart(), and onTestEnd() signatures are part of the stable extension contract — implementors bind to these exact names and parameter types.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## Polymorphic Extension Pattern
- **Pattern**: STRATEGY_PATTERN
- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing this file.

## se.deversity.asynctest.spi.DetectorFactory

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public SPI interface for ServiceLoader-based detector discovery. type(), isEnabledFor(), and create() signatures are part of the stable factory contract — implementors bind to these exact names and parameter types.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## se.deversity.asynctest.report.Violation

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Java record — fields are final by language. Collection fields are deep-copied to immutable views in the canonical constructor.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## se.deversity.asynctest.spi.DetectorRegistry

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Effectively immutable after build() — the EnumMap is populated only in the private constructor and never mutated thereafter; safe to publish to multiple threads and read-only views over an EnumMap populated once at construction.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

### Rules for method analyzeAll
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: Each Detector.analyze() must return the same violations for the same observed state (the SPI contract). Calling analyzeAll() N times on a quiescent registry yields N identical lists; do not introduce stateful side-effects in analyze().

## se.deversity.asynctest.report.JsonFormatter

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## se.deversity.asynctest.report.MarkdownFormatter

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## se.deversity.asynctest.AsyncTestListenerRegistry.Registration

### Rules for method close
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: Guarded by the `closed` volatile flag; second close() returns early before touching the registry. Covered by `registrationClose_isIdempotent` test.

## se.deversity.asynctest.report.JUnitXmlReportListener

### Rules for parameter JUnitXmlReportListener.onStructuredReport(java.lang.String,se.deversity.asynctest.diagnostics.IssueSeverity,java.lang.String)#report
- **Target Filters**: XSS
- **Rule**: Run raw input strings through approved sanitizers.

## se.deversity.asynctest.report.JsonReportListener

### Rules for parameter JsonReportListener.onStructuredReport(java.lang.String,se.deversity.asynctest.diagnostics.IssueSeverity,java.lang.String)#report
- **Target Filters**: XSS
- **Rule**: Run raw input strings through approved sanitizers.
<!-- VIBETAGS-END -->
