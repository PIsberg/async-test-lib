---
paths: ["**/AsyncTestAgent.java"]
---

<!-- VIBETAGS-START -->
# Rules for AsyncTestAgent

## Core Functionality
- **Sensitivity**: Critical
- **Note**: The INSTALLED gate must stay at-most-once per JVM: every entry point (premain, agentmain, selfAttach) races on the same compareAndSet, and a second transformer would double-weave accesses and double-count every one. premain installs without retransformation because classes are woven as they load; agentmain must keep RETRANSFORMATION + disableClassFormatChanges(), which is only safe while neither weaver adds members — the Advice is a method-entry prologue, and FieldAccessWeaver inserts a stack-neutral, branch-free call before each field instruction, so frames stay valid and only maxStack grows (COMPUTE_MAXS, never COMPUTE_FRAMES, which would load classes from inside the agent). Nothing may throw out of premain — an exception there aborts JVM startup, which is why install() catches Throwable and releases the gate rather than propagating. The Premain-Class / Agent-Class manifest entries live in this module's jar, which is why attaching uses -javaagent:async-test-agent.jar.
<!-- VIBETAGS-END -->
