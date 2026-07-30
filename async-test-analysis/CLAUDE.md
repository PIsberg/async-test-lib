# async-test-analysis (module)

`StaticPinningScanner` — an ASM pre-scanner that finds Loom pinning sites in compiled classes before
a test run. One class, one dependency.

This module depends on nothing else in the project and nothing depends on it. Keep it that way: both
directions are enforced by `ArchitectureTest`. If it starts needing the runner or a detector, that is
a design question, not a dependency to add.

<!-- VIBETAGS-START -->
<!-- VIBETAGS-END -->
