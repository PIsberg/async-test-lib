package se.deversity.asynctest.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AsyncTestAgentTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        TelemetryRegistry.stop();
    }

    @Test
    void testFieldAccessAdviceDirectly() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> recorded = Collections.synchronizedList(new ArrayList<>());

        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            recorded.add(targetField + ":" + isWrite);
            latch.countDown();
        });

        // Trigger the Advice enter method directly.
        AsyncTestAgent.FieldAccessAdvice.enter("TestClass", "setCount");

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS), "Direct advice enter did not trigger registry callback");
        assertEquals(List.of("TestClass#setCount:true"), recorded);
    }

    @Test
    void testDynamicByteBuddyInstrumentation() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> recorded = Collections.synchronizedList(new ArrayList<>());

        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            recorded.add(targetField + ":" + isWrite);
            latch.countDown();
        });

        // Use Byte Buddy to dynamically construct and instrument a class at runtime
        // matching the exact getter/setter advice routing.
        Class<?> dynamicType = new ByteBuddy()
                .subclass(Object.class)
                .name("se.deversity.asynctest.agent.DynamicTargetClass")
                .defineMethod("setValue", void.class, Modifier.PUBLIC)
                .intercept(FixedValue.originType()) // No-op body
                .visit(Advice.to(AsyncTestAgent.FieldAccessAdvice.class)
                        .on(ElementMatchers.named("setValue")))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();

        // Instantiate and invoke the instrumented method.
        Object instance = dynamicType.getDeclaredConstructor().newInstance();
        dynamicType.getMethod("setValue").invoke(instance);

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS), "Instrumented method call was not captured by telemetry");
        assertEquals(1, recorded.size());
        assertTrue(recorded.get(0).contains("DynamicTargetClass#setValue:true"));
    }
}
