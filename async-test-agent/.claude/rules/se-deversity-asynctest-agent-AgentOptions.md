---
paths: ["**/AgentOptions.java"]
---

<!-- VIBETAGS-START -->
# Rules for AgentOptions

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: The class is package-private but the agentArgs grammar it parses is public surface: users type it on the -javaagent: command line. Key names (includes/excludes/debug), the comma-or-semicolon separator, the bare-token continuation that lets one key carry several values, and case-insensitive key matching are all part of that contract — changing any of them breaks existing launch scripts silently. Parsing must stay total: it is called from premain, where a thrown exception aborts JVM startup, so unknown keys are ignored and malformed input degrades to the default instrument-everything behaviour rather than failing.
<!-- VIBETAGS-END -->
