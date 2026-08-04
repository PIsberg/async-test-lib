<!-- VIBETAGS-START -->
# Rules for async-test-instrumentation

## Core Functionality

### se.deversity.asynctest.analysis.StaticPinningScanner
- **Sensitivity**: High
- **Note**: The whole module is this one class plus ASM, and ArchitectureTest pins both directions: nothing here may reference the library, and asm may not leak out of here. Keep the analysis one-directional — if the scanner starts needing the runner or a detector, that is a design question, not a dependency to add. The asymmetry in the findings is deliberate and must be preserved: monitor depth is tracked within a single method body only, so cross-method synchronization yields false negatives, and MONITOREXIT on exception-handler edges may undercount depth. False negatives are acceptable here; a false positive is not, because the scanner runs without executing tests and has no way to confirm a site.
<!-- VIBETAGS-END -->
