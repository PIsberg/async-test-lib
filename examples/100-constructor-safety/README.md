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

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsThisEscape` in `EventEmitterTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **ConstructorSafetyValidator** will report construction started but fields accessed
   before construction completed.

## The Fix

Register `this` only after all fields are initialized — move `EventRegistry.register(this)`
to the last line of the constructor, or provide a separate `init()` factory method.
