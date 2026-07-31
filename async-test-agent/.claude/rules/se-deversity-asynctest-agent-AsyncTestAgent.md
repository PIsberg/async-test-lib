---
paths: ["**/AsyncTestAgent.java"]
---

<!-- VIBETAGS-START -->
# Rules for AsyncTestAgent

## Core Functionality
- **Sensitivity**: Critical
- **Note**: The INSTALLED gate must stay at-most-once per JVM: every entry point (premain, agentmain, selfAttach) races on the same compareAndSet, and a second transformer would double-weave field accessors and double-count every access. premain installs without retransformation because classes are woven as they load; agentmain must keep RETRANSFORMATION + disableClassFormatChanges(), which is only safe while the Advice stays a method-entry prologue that adds no fields, methods or interfaces. Nothing may throw out of premain — an exception there aborts JVM startup. The Premain-Class / Agent-Class manifest entries live in this module's jar, which is why attaching uses -javaagent:async-test-agent.jar.
<!-- VIBETAGS-END -->
