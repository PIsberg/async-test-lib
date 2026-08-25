# Example 100 — Constructor Safety (this-escape)

Demonstrates **ConstructorSafetyValidator** catching `this`-escape from a constructor.

## The Problem

`EventEmitter` publishes the `this` reference to a shared `EventRegistry` as the very
first action in its constructor — before `this.name` and `this.listeners` are set.
Other threads that poll the registry can immediately call methods on a partially
constructed object, seeing `null` fields and causing `NullPointerException` at runtime.

```java
public EventEmitter(String name) {
    EventRegistry.register(this);  // BUG: this escapes before fields are initialized
    this.name = name;
    this.listeners = new ArrayList<>();
}
```

## Who has to see it, and from where

`ConstructorSafetyValidator` compares the **accessing** thread against the **constructing** one.
A field touched by a different thread before the constructor finished is unsafe publication; the
same field touched by the thread still running the constructor is just a constructor.

That comparison is why this example used to report nothing. Its demonstration recorded against a
sentinel `Object` that the emitter knew nothing about, and did every recording on one thread, so
the condition was unreachable by construction. Empty report, three runs out of three
(issue #346).

The registration listener is what makes it real. `EventRegistry` notifies listeners when an
emitter registers, the emitter registers from inside its own constructor, and the listener reads
the emitter from another thread. `testConstructor_registrationListenerSeesAHalfBuiltObject` shows
the result without any detector: the listener holds a fully-typed `EventEmitter` whose `name` is
`null`.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsThisEscape` in `EventEmitterTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. It fails:

```
CONSTRUCTOR SAFETY ISSUES DETECTED:

Objects accessed by multiple threads during construction:
  - EventEmitter: Accessed by 1 threads during construction
  Fix: Don't share object reference until constructor completes
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

One thing to know when reading this validator's output: `hasIssues()` also covers
`possiblyIncompleteConstructions`, which flags any construction that completed in under a
microsecond - and an empty constructor completes in under a microsecond. The negative-direction
test here therefore asserts on `unsafeObjects` specifically. See issue #357.

## The Fix

Register `this` only after all fields are initialized — move `EventRegistry.register(this)`
to the last line of the constructor, or provide a separate `init()` factory method.
