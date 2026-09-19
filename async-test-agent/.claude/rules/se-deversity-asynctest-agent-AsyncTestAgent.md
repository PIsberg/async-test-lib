---
paths: ["**/AsyncTestAgent.java"]
---

<!-- VIBETAGS-START -->
# Rules for AsyncTestAgent

## Core Functionality
- **Sensitivity**: Critical
- **Note**: The INSTALLED gate must stay at-most-once per JVM: every entry point (premain, agentmain, selfAttach) races on the same compareAndSet, and a second transformer would double-weave accesses and double-count every one. premain installs without retransformation because classes are woven as they load; agentmain must keep RETRANSFORMATION + disableClassFormatChanges(), which is only safe while neither weaver adds members — the Advice is a method-entry prologue, and FieldAccessWeaver inserts a stack-neutral, branch-free call before each field instruction, a DUP plus call after a reference getAndSet on an atomic slot (the ownership take, #555), and replaces an int VarHandle, AtomicIntegerFieldUpdater, AtomicBoolean or AtomicInteger compareAndSet/set call, or a value-returning release such as getAndSet, decrementAndGet or compareAndExchange, with a static hook consuming the same stack and returning the same result, plus one POP where a VarHandle call site declared a void result (the spinlock, #554, #558, #658), so frames stay valid and only maxStack grows (COMPUTE_MAXS, never COMPUTE_FRAMES, which would load classes from inside the agent). With fields=true, install and the transformer also open java.util.concurrent.atomic to one module per woven loader and to nothing else: the module of the TelemetryRegistry copy that loader resolves, never the woven loader or its ancestors as such (UpdaterAccess, #659, #668). A woven class in a named module also gets a read edge to that copy. The library resolves an updater bound before the attach by reading the JDK implementation from that copy (JdkUpdaterShapeCanaryTest pins the shape it reads), and the opening must fail silently like the rest of install. Nothing may throw out of premain — an exception there aborts JVM startup, which is why install() catches Throwable and releases the gate rather than propagating. The Premain-Class / Agent-Class manifest entries live in this module's jar, which is why attaching uses -javaagent:async-test-agent.jar.
<!-- VIBETAGS-END -->
