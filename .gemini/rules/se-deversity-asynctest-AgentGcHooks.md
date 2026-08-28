<!-- VIBETAGS-START -->
# Rules for AgentGcHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites, through the static substitution path: the method name and erased signature here are matched by CollectionAccessWeaver.GC_ENTRIES and cannot change independently of it. Record before collecting, not after: the finding is that the call was made, and a hook that recorded afterwards would lose the event if the collection never returned. The hook must perform the original System.gc() so that weaving does not change what the program does - a substitution that silently dropped the collection would alter behaviour the test may depend on, which is the one thing no weave may do. Unlike AgentSleepHooks there is deliberately no HeldLocks guard: an explicit collection is the bug whether or not a lock is held, and adding a guard would silence the common case. The stack walk must skip se.deversity.asynctest frames, or every finding would name this class instead of the caller.
<!-- VIBETAGS-END -->
