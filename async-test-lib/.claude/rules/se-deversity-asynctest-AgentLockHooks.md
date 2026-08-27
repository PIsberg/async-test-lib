---
paths: ["**/AgentLockHooks.java"]
---

<!-- VIBETAGS-START -->
# Rules for AgentLockHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites: method names and erased signatures are matched by CollectionAccessWeaver.lockSubstitutions and cannot change independently of it. The acquire-after / release-before ordering is a safety property, not a style choice - recording a lock as held before it is actually held would make another thread's racing access read as guarded, which is the one error direction this library must never take. Every hook must perform the original operation and propagate its exceptions unchanged. The detector delivery inherits the same acquire-after / release-before ordering as the lockset and must keep it: LockOrderValidator builds its nesting edges from the order these calls arrive in, so delivering an acquisition early would invent a hold-and-wait edge that never existed. It resolves each detector through a null-returning AsyncTestContext accessor because a woven call site runs in code that does not know a test exists, so no context is the ordinary case and must cost a null check rather than an exception. DetectorFeeds lists these three as AGENT-fed and DetectorFeedCoverageTest reads this file to check the delivery is still here, so removing a call means reclassifying the detector in the same change.
<!-- VIBETAGS-END -->
