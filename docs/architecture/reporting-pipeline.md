# Reporting Pipeline

> Part of the [architecture documentation](../ARCHITECTURE.md).

## Reporting Pipeline (1.6.0)

In 1.6.0 detector findings gained a structured representation alongside the
historical free-text `String` reports. Both flow through the runner unchanged;
new tooling consumes the structured form.

```
Detector observation (recordAccess, etc.)
        │
        ▼
analyze() → legacy String reports     +     analyze().structuredViolations: List<Violation>
                                                      │
                                                      ▼
                                     Formatter.format(...)
                                     ├── MarkdownFormatter  → PR comments, CI logs
                                     └── JsonFormatter      → dashboards / SARIF / IDE plugins
```

`Violation` (`se.deversity.async-test-lib.report`) is an immutable record:
`(detector, severity: IssueSeverity, message, sites: List<SiteCapture.Site>, attributes: Map<String,Object>, when: Instant)`.
The canonical constructor enforces non-blank `detector`, defaults `sites` and
`attributes` to empty immutables, and stamps `when` with `Instant.now()` if null.

**Source-line attribution** is captured by `SiteCapture.capture()` which performs
a single `StackWalker.walk` and returns the first non-framework `StackFrame` as a
`Site(className, methodName, fileName, lineNumber)` record. Framework frames are
filtered by package prefix (`runner.`, `extension.`, `benchmark.`, JDK reflection,
`java.util.concurrent`, JUnit, Gradle) and class-name suffix (`Detector`,
`Monitor`, `Validator`, `SiteCapture`). Detectors that adopt the helper add
`Set<SiteCapture.Site>` to their per-instance state; the `Set` dedupes by
`(class, line)` so a tight loop on one call site contributes a single
attribution. `SharedMessageDigestDetector` is the canary; other detectors
migrate incrementally.

The two formatters ship with no external dependencies. JSON output uses a small
hand-rolled writer with proper escape handling for `\"`, `\\`, `\n`, `\r`, `\t`,
and control characters (`\\u00xx`).

---

