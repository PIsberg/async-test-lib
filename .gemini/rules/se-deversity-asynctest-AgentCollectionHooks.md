<!-- VIBETAGS-START -->
# Rules for AgentCollectionHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites, not from source: the method names and erased signatures are matched by CollectionAccessWeaver and cannot change independently of it. Every hook must end by performing the original operation and must never throw on the recording path - it runs inside the user's code, so an exception here surfaces as a failure in their test. Recording is best-effort by design: no context, a disabled detector, or a type the library knows is thread-safe all mean record nothing and delegate. A queue offer or take hook with a trailing Object parameter is the variant the weaver calls with the enclosing method's own monitor: this in an instance method, the class in a static synchronized one. A synchronized method holds it with no instruction to say so (#796), and a helper that method calls holds it too (#751), so the hook counts it among the held locks exactly when Thread.holdsLock says so, and the two-argument form delegates with null.
<!-- VIBETAGS-END -->
