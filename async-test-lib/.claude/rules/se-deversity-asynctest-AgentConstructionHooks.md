---
paths: ["**/AgentConstructionHooks.java"]
---

<!-- VIBETAGS-START -->
# Rules for AgentConstructionHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent inserts, not from source: ConstructionWeaver matches these method names and erased signatures at weave time, so none can change independently of it. These are inserted calls, not substitutions: there is no original operation to perform, and each must hand back exactly what the stack held (linkedHashMapAccessOrder returns its argument unchanged, or the constructor would build a map in the wrong order). They run inside user constructors, so they must never throw and must not allocate per call once a thread has made its first map.
<!-- VIBETAGS-END -->
