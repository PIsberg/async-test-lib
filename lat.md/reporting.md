# Reporting

How detector findings reach humans and CI: structured violations, pluggable formatters, lifecycle listeners, and the severity gate that decides pass/fail.

## Violation and findings

`report/Violation.java` is an immutable Java record — collection fields are deep-copied to immutable views in the canonical constructor.

`report/DetectorFinding.java` carries individual findings; `diagnostics/SiteCapture.Site` (also a record) pins findings to code sites captured on the hot path.

## Formatter strategy

`report/Formatter.java` is the public strategy SPI: `format(List<Violation>)` — the exact signature is load-bearing because user lambdas bind to it.

Built-ins: `JsonFormatter`, `MarkdownFormatter`. New output formats are new strategy implementations, never switches inside existing ones.

## Listeners

`AsyncTestListenerRegistry` (public API: `register`, `unregister`, `clearAll`, `fireXxx`) broadcasts lifecycle events to `AsyncTestListener` implementations.

Events cover invocation started/completed, test failed, timeout, and detector reports. `unregister`, `clearAll`, and `Registration.close()` are idempotent. Shipped listeners in `report/`: `JUnitXmlReportListener` and `JsonReportListener` (their `onStructuredReport` inputs are XSS-sanitized before rendering — keep the sanitization), `StrictModeListener`, and `ReportListeners` helpers. `report/Baseline.java` supports record-then-suppress workflows: known findings are recorded instead of failing, and suppressed on later runs.

## Gating

After a successful run, the analyze-and-gate step collects reports once (memoized per execute) and fails the test when any finding's severity reaches `config.failOn`.

Severity inference is described in [[detectors#Severity]]. The gate deliberately does not run when the test already failed on its own — no synthetic second failure ([[execution-model#Failure paths]]). Timeout and failure paths still print reports and fire listeners so diagnostics are never lost.
