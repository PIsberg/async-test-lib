---
paths: ["**/BodyThreadFailures.java"]
---

<!-- VIBETAGS-START -->
# Rules for BodyThreadFailures

## Core Functionality
- **Sensitivity**: High
- **Note**: This sets the JVM-wide default uncaught exception handler inside somebody else's test suite, so three things must hold. install and uninstall stay counted and paired in a finally (ConcurrencyRunner.execute): runs overlap when test classes execute in parallel, and a boolean, or an uninstall skipped on a failure path, leaves the handler in place for the rest of the build or restores the user's handler while another run still needs this one. The last uninstall restores only when the default is still this handler, so a handler the suite set during a run is never clobbered. And died() always hands the death on, to the default found at install or as ThreadGroup's own print, whatever recording does: a handler that swallows turns every uncaught exception in the build silent. Attribution may miss a death (an outliving thread, a static pool without the agent) but must never give one to a run that did not start the thread, because an AssertionError fails the test it lands in (BodyStartedThreadFailureTest, #1253).
<!-- VIBETAGS-END -->
