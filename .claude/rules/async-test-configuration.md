---
paths: ["**/AsyncTest.java", "**/AsyncTestConfig.java", "**/DetectorType.java", "**/DetectorRegistry.java", "**/Preset.java"]
---

<!-- VIBETAGS-START -->
# Rules for async-test-configuration

## se.deversity.asynctest.DetectorType

## Locked Status
- **Reason**: Each enum constant requires synchronized changes in five places: (1) @AsyncTest attribute, (2) AsyncTestConfig field, (3) AsyncTestConfig.Builder default, (4) both branches of AsyncTestConfig.build() (detectAll block + excludes block), and (5) DetectorRegistry constructor. Adding a value here in isolation breaks the system.

## se.deversity.asynctest.AsyncTestConfig

## Context & Focus
- **Focus**: Maintain strict 1:1 mapping between @AsyncTest attributes, Builder fields, from(AsyncTest), build() logic, and DetectorRegistry
- **Avoid**: mutable state — this class must remain immutable after construction

## Core Functionality
- **Sensitivity**: Critical
- **Note**: Adding a new detector requires synchronized changes across six places: @AsyncTest attribute, AsyncTestConfig field, Builder default, from(AsyncTest) call chain, build() detectAll/excludes blocks, and DetectorRegistry constructor.

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Immutable snapshot of @AsyncTest parameters to ensure thread safety.

### Rules for field enableBenchmarking
- **Flag**: 'async-test.benchmarking.enabled' (default: false)
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.

### Rules for field licenseMockMode
- **Flag**: 'license.mock.mode' (default: false)
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.

## se.deversity.asynctest.DetectorRegistry

## Context & Focus
- **Focus**: Each new detector requires exactly three steps in this class: (1) a final field declaration, (2) conditional construction in the constructor keyed on the config flag, (3) an analyzeAll() call in the correct phase block. All three steps must be added together.
- **Avoid**: partial patterns — a field without construction or analysis silently skips detection

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: Guards conditional access to internal detector initialization and phase blocks.

## se.deversity.asynctest.AsyncTest

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public annotation API used directly in user test methods. Attribute names, types, and defaults are part of the stable public API — any change is a breaking change for all consumers.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## se.deversity.asynctest.Preset

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Enum constants — JVM guarantees structural immutability. Internal enabled-set is captured at class init.

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->
