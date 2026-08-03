---
paths: ["**/DetectorType.java", "**/AsyncTestConfig.java", "**/DetectorRegistry.java", "**/AsyncTest.java", "**/Preset.java"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-configuration

## Locked Status

### se.deversity.asynctest.DetectorType
- **Reason**: Adding or removing a constant requires synchronized changes in five places: (1) @AsyncTest attribute, (2) AsyncTestConfig field, (3) AsyncTestConfig.Builder default, (4) the resolution line in AsyncTestConfig.build() ((detectAll || flag) && !excludes.contains(TYPE)), and (5) DetectorRegistry constructor. Adding a value here in isolation compiles and detects nothing. The lock is on the constant set, not the file: editing javadoc on existing constants cannot break that invariant and needs no ceremony.

## Mirrored — Keep In Sync

### se.deversity.asynctest.DetectorType
- **Rule**: Free to change, but every mirror must change in the same commit.
- **Mirrors**: se.deversity.asynctest.AsyncTest, se.deversity.asynctest.AsyncTestConfig, se.deversity.asynctest.DetectorRegistry, se.deversity.asynctest.spi.LegacyDetectorFactories, META-INF/async-test/builtin-detector-factories
- **Reason**: A detector is only reachable from the public API when all of these agree. The enum constant is the name users type in @AsyncTest(excludes=...); the annotation attribute, the config field and its Builder default carry it through resolution; the registry constructor instantiates it; and the SPI factory plus its entry in the built-in factory list are what detectAll loads. Adding the constant alone compiles and silently detects nothing.
- **Enforced by**: se.deversity.asynctest.spi.AllDetectorsSpiCoverageTest

## Context & Focus

### se.deversity.asynctest.AsyncTestConfig
- **Focus**: Maintain strict 1:1 mapping between @AsyncTest attributes, Builder fields, from(AsyncTest), build() logic, and DetectorRegistry
- **Avoid**: mutable state — this class must remain immutable after construction

### se.deversity.asynctest.DetectorRegistry
- **Focus**: Each new detector requires exactly three steps in this class: (1) a final field declaration, (2) conditional construction in the constructor keyed on the config flag, (3) an analyzeAll() call in the correct phase block. All three steps must be added together.
- **Avoid**: partial patterns — a field without construction or analysis silently skips detection

## Core Functionality

### se.deversity.asynctest.AsyncTestConfig
- **Sensitivity**: Critical
- **Note**: Adding a new detector requires synchronized changes across six places: @AsyncTest attribute, AsyncTestConfig field, Builder default, from(AsyncTest) call chain, build() detectAll/excludes blocks, and DetectorRegistry constructor.

## Immutable Type
- **Rule**: These types are immutable. Never introduce non-final fields, setters, or mutating methods.

### se.deversity.asynctest.AsyncTestConfig
- **Note**: Immutable snapshot of @AsyncTest parameters to ensure thread safety.

### se.deversity.asynctest.Preset
- **Note**: Enum constants — JVM guarantees structural immutability. Internal enabled-set is captured at class init.

## Feature Flag Gate
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.

### se.deversity.asynctest.AsyncTestConfig.enableBenchmarking
- **Flag**: 'async-test.benchmarking.enabled' (default: false)

### se.deversity.asynctest.AsyncTestConfig.licenseMockMode
- **Flag**: 'license.mock.mode' (default: false)

## Thread-Safety Guarantee

### se.deversity.asynctest.DetectorRegistry
- **Strategy**: SYNCHRONIZED
- **Note**: Guards conditional access to internal detector initialization and phase blocks.

## Contract-Frozen Signature

### se.deversity.asynctest.AsyncTest
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public annotation API used directly in user test methods. Attribute names, types, and defaults are part of the stable public API — any change is a breaking change for all consumers.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
- **Applies to**: `se.deversity.asynctest.AsyncTest`, `se.deversity.asynctest.Preset`
<!-- VIBETAGS-END -->
