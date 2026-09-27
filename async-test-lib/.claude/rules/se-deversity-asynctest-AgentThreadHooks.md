---
paths: ["**/AgentThreadHooks.java"]
---

<!-- VIBETAGS-START -->
# Rules for AgentThreadHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites: method names and erased signatures of the hooks CollectionAccessWeaver.THREAD_ENTRIES substitutes (threadStart, threadJoin, threadIsAlive, threadSetDaemon, threadStartVirtual and the builder's threadBuilderStart, threadBuilderDaemon and threadBuilderUnstarted) and of threadConstructed, which ThreadConstructionWeaver inserts after each thread a woven class constructs, are matched at weave time, and threadWeavingInstalled is invoked by name from AsyncTestAgent after the transformer is installed; none of them can change independently of the agent. Every substituting hook must perform the original call on the receiver, or what the JDK does for it (a builder's start is unstarted then start), and propagate what it throws unchanged. threadConstructed replaces nothing and runs inside user constructors, so it performs no call and must not throw. threadSetDaemon records the decision only after setDaemon returned, so a call that throws (an already started thread) records nothing.
<!-- VIBETAGS-END -->
