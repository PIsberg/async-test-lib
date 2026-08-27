<!-- VIBETAGS-START -->
# Rules for AgentSharedInstanceHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites: the method names and erased signatures here are matched by CollectionAccessWeaver.SHARED_INSTANCE_ENTRIES and cannot change independently of it. Every hook must perform the original operation and propagate its exceptions unchanged, and must record before delegating only where the original cannot throw first - the detector's question is 'did two threads touch this instance', which a call that threw still answers. The receiver types are deliberately concrete and free of thread-safe subclasses: adding one that has a safe subclass, Random being the standing example with ThreadLocalRandom, turns every substituted call site into a potential false positive on correct code.
<!-- VIBETAGS-END -->
