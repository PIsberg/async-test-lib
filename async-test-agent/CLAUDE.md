# async-test-agent (module)

The optional Byte Buddy field-access agent. Two classes, and the only place in the project allowed
to reference `net.bytebuddy` — `ArchitectureTest.bytebuddy_is_confined_to_the_agent` enforces that.

This module carries the `Premain-Class` / `Agent-Class` manifest entries. They live here, not in the
library JAR, which is why attaching uses `-javaagent:async-test-agent.jar`.

Nothing may depend on this module: it is reached through `-javaagent:` or
`AsyncTestAgent.selfAttach()`, never by a compile-time reference. A dependency pointing back at the
library is fine; one pointing at the agent is not.

<!-- VIBETAGS-START -->
<!-- VIBETAGS-END -->
