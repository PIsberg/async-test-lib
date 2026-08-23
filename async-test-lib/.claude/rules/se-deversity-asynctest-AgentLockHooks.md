---
paths: ["**/AgentLockHooks.java"]
---

<!-- VIBETAGS-START -->
# Rules for AgentLockHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites: method names and erased signatures are matched by CollectionAccessWeaver.lockSubstitutions and cannot change independently of it. The acquire-after / release-before ordering is a safety property, not a style choice - recording a lock as held before it is actually held would make another thread's racing access read as guarded, which is the one error direction this library must never take. Every hook must perform the original operation and propagate its exceptions unchanged.
<!-- VIBETAGS-END -->
