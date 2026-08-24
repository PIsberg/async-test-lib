# File Structure

> Part of the [architecture documentation](../ARCHITECTURE.md).

## File Structure

```
src/main/java/se/deversity/asynctest/
├── AsyncTest.java                    # Main annotation (incl. threadCounts, preset, replaySeed since 1.6.0)
├── AsyncTestConfig.java              # Immutable configuration snapshot
├── AsyncTestContext.java             # ThreadLocal context + replaySeed accessor
├── AsyncTestListener.java            # Observability listener interface
├── AsyncTestListenerRegistry.java    # Listener registry (+ scoped Registration/Snapshot since 1.6.0)
├── NoopAsyncTestListener.java        # No-op listener for opt-out
├── DetectorRegistry.java             # Legacy detector lifecycle (powers existing 90+)
├── DetectorType.java                 # Detector enumeration
├── Preset.java                       # NEW in 1.6.0 — curated detector bundles
├── BeforeEachInvocation.java         # Lifecycle annotation
├── AfterEachInvocation.java          # Lifecycle annotation
├── AsyncAssert.java                  # Async assertion helper (+ awaitAsync since 1.6.0)
├── extension/
│   ├── AsyncTestExtension.java       # JUnit 5 extension (matrix fan-out since 1.6.0)
│   └── AsyncTestInvocationInterceptor.java  # Interceptor (threadCount override since 1.6.0)
├── runner/
│   ├── ConcurrencyRunner.java        # Main execution engine
│   └── LicenseGuard.java             # NEW in 1.6.0 — process-wide license cache
├── diagnostics/                      # 146 detector implementations
│   ├── Phase1DetectorSet.java        # Phase 1 detector group
│   ├── SiteCapture.java              # NEW in 1.6.0 — source-line attribution helper
│   ├── DeadlockDetector.java
│   ├── ... (95 more across phases 1–12)
│   ├── DaemonThreadHygieneDetector.java   # NEW in 1.6.0 — Phase 13
│   ├── NotifyWithoutMonitorDetector.java  # NEW in 1.6.0 — Phase 13
│   ├── SharedSecureRandomDetector.java    # NEW in 1.6.0 — Phase 13
│   ├── WeakHashMapSharedDetector.java     # NEW in 1.6.0 — Phase 13
│   ├── JdbcConnectionSharedDetector.java  # NEW in 1.6.0 — Phase 13
│   ├── StableValueMisuseDetector.java          # NEW in 1.7.0 — standalone, JDK 25/26 (JEP 502)
│   ├── StructuredTaskScopeMisuseDetector.java  # NEW in 1.7.0 — standalone, JDK 25/26 (JEP 505)
│   ├── GathererConcurrencyMisuseDetector.java  # NEW in 1.7.0 — standalone, JDK 24+ (JEP 485)
├── report/                           # NEW package in 1.6.0 — structured reporting
│   ├── Violation.java                # (detector, severity, message, sites, attributes, when)
│   ├── Formatter.java                # functional interface List<Violation> → String
│   ├── MarkdownFormatter.java        # PR comments / CI logs
│   └── JsonFormatter.java            # dashboards / SARIF / IDE plugins
├── spi/                              # NEW package in 1.6.0 — Detector SPI
│   ├── Detector.java                 # SPI interface: type(), analyze(), lifecycle hooks
│   ├── DetectorFactory.java          # ServiceLoader-discovered factory
│   ├── DetectorRegistry.java         # SPI-driven registry (coexists with legacy)
│   └── adapters/
│       ├── LegacyDetectorAdapter.java               # generic reflective wrapper
│       ├── LegacyDetectorFactories.java             # 99 inner-class factories (1 per DetectorType)
│       └── SharedMessageDigestDetectorFactory.java  # typed canary adapter (template)
└── benchmark/                        # Benchmarking module
    ├── BenchmarkRecorder.java
    ├── BenchmarkComparator.java
    ├── BenchmarkResult.java
    ├── BenchmarkComparisonResult.java
    └── BenchmarkRegressionException.java

src/main/resources/META-INF/services/
└── se.deversity.asynctest.spi.DetectorFactory  # NEW in 1.6.0 — ServiceLoader registration
```

---

