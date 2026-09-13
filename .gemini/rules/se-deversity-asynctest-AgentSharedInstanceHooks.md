<!-- VIBETAGS-START -->
# Rules for AgentSharedInstanceHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites: the method names and erased signatures here are matched by CollectionAccessWeaver.SHARED_INSTANCE_ENTRIES and cannot change independently of it. Every hook must perform the original operation and propagate its exceptions unchanged, and must record before delegating only where the original cannot throw first - the detector's question is 'did two threads touch this instance', which a call that threw still answers. A receiver type that has thread-safe subclasses may be woven only when its hook checks the runtime type before recording: DateFormat records only a SimpleDateFormat and Appendable only a StringBuilder (#542). Without that check, Random being the standing example with ThreadLocalRandom, every substituted call site becomes a potential false positive on correct code.
<!-- VIBETAGS-END -->
