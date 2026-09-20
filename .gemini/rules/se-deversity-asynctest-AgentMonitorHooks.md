<!-- VIBETAGS-START -->
# Rules for AgentMonitorHooks

## Contract-Frozen Signature
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Called from bytecode the agent rewrites: method names and erased signatures here are matched by CollectionAccessWeaver.MONITOR_ENTRIES, and loopBackEdge by name with a ()V descriptor, so none can change independently of the weaver. Every hook must perform the original call on the receiver and propagate what it throws unchanged: InterruptedException, and the IllegalMonitorStateException of a call made without the monitor, which is why nothing is recorded unless Thread.holdsLock(receiver). Record the wait before wait() releases the monitor and the wakeup after it is reacquired, in a finally, so a notify is judged against the threads really waiting; record a notify before making it. loopBackEdge takes nothing and returns nothing because the weaver inserts it in front of a jump whose operands are already on the stack.
<!-- VIBETAGS-END -->
