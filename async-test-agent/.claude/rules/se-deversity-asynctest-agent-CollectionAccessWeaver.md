---
paths: ["**/CollectionAccessWeaver.java"]
---

<!-- VIBETAGS-START -->
# Rules for CollectionAccessWeaver

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: The hook class name and the method names here are the other half of AgentCollectionHooks and AgentLockHooks: they are matched by erased signature at weave time, so renaming a hook or changing a parameter type breaks weaving with a NoSuchMethodError inside user code rather than at compile time. Each substitution must consume exactly the stack its original invocation consumed - stack-shape-neutral and member-free is what keeps retransformation safe under disableClassFormatChanges(). The visitor must never touch invokedynamic: parsing its constants is what made every Java record fail to instrument when this went through MemberSubstitution. Collection weaving is opt-in (collections=true) because it instruments every listed call in every matched class.
<!-- VIBETAGS-END -->
