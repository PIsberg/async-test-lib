---
paths: ["**/AgentThreadHooks.java"]
---

<!-- VIBETAGS-START -->
# Rules for AgentThreadHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites: method names and erased signatures of threadStart, threadJoin and threadSetDaemon are matched by CollectionAccessWeaver.THREAD_ENTRIES, and threadWeavingInstalled is invoked by name from AsyncTestAgent after the transformer is installed; none of them can change independently of the agent. Every hook must perform the original call on the receiver and propagate what it throws unchanged. threadSetDaemon records the decision only after setDaemon returned, so a call that throws (an already started thread) records nothing.
<!-- VIBETAGS-END -->
