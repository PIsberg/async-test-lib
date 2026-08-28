---
paths: ["**/AgentConcurrencyUtilHooks.java"]
---

<!-- VIBETAGS-START -->
# Rules for AgentConcurrencyUtilHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites: method names and erased signatures here are matched by CollectionAccessWeaver.CONCURRENCY_ENTRIES and cannot change independently of it. Every hook must perform the original operation and propagate its exceptions unchanged, InterruptedException included - these types throw it as a matter of course and swallowing one would change the interruption semantics of the code under test. Record after acquiring and before releasing, the containment rule AgentLockHooks documents, and record nothing when the underlying call throws. offer, poll and the timed await must record their actual return value: the boolean a caller discards is the whole bug these detectors report.
<!-- VIBETAGS-END -->
