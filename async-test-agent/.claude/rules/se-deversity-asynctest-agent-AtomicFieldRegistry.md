---
paths: ["**/AtomicFieldRegistry.java"]
---

<!-- VIBETAGS-START -->
# Rules for AtomicFieldRegistry

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Resolved reflectively so async-test-agent keeps its zero-dependency boundary on async-test-lib, which ArchitectureTest enforces in both directions. The method name and signature must match TelemetryRegistry.atomicallyManaged(String). Every failure path here must stay silent: this only ever suppresses findings, so losing it degrades precision rather than correctness, while throwing out of a class transformation would fail the user's test run.
<!-- VIBETAGS-END -->
