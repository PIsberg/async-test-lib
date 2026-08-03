<!-- VIBETAGS-START -->
# Rules for async-test-instrumentation

## Core Functionality

### se.deversity.asynctest.agent.AsyncTestAgent
- **Sensitivity**: Critical
- **Note**: The INSTALLED gate must stay at-most-once per JVM: every entry point (premain, agentmain, selfAttach) races on the same compareAndSet, and a second transformer would double-weave field accessors and double-count every access. premain installs without retransformation because classes are woven as they load; agentmain must keep RETRANSFORMATION + disableClassFormatChanges(), which is only safe while the Advice stays a method-entry prologue that adds no fields, methods or interfaces. Nothing may throw out of premain — an exception there aborts JVM startup. The Premain-Class / Agent-Class manifest entries live in this module's jar, which is why attaching uses -javaagent:async-test-agent.jar.

## Contract-Frozen Signature

### se.deversity.asynctest.agent.AgentOptions
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: The class is package-private but the agentArgs grammar it parses is public surface: users type it on the -javaagent: command line. Key names (includes/excludes/debug), the comma-or-semicolon separator, the bare-token continuation that lets one key carry several values, and case-insensitive key matching are all part of that contract — changing any of them breaks existing launch scripts silently. Parsing must stay total: it is called from premain, where a thrown exception aborts JVM startup, so unknown keys are ignored and malformed input degrades to the default instrument-everything behaviour rather than failing.
<!-- VIBETAGS-END -->
