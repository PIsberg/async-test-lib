package se.deversity.asynctest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every boolean setter on {@link AsyncTestConfig.Builder} returns the builder it was called on.
 *
 * <p>A caller chains them: {@code AsyncTestConfig.builder().detectRaceConditions(true).build()}.
 * The tests that pin what each setter selects call it as a statement, so a setter returning
 * {@code null} or a fresh builder passed all of them; the mutation run of 2026-10-08 found 121 of
 * the 146 detector setters with that mutant alive.
 */
class AsyncTestConfigBuilderFluencyTest {

    @Test
    @DisplayName("every boolean setter returns the same builder, for both values")
    void everyBooleanSetterChains() throws IllegalAccessException {
        List<String> broken = new ArrayList<>();
        int setters = 0;
        for (Method method : AsyncTestConfig.Builder.class.getMethods()) {
            if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != boolean.class
                    || method.getReturnType() != AsyncTestConfig.Builder.class
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            setters++;
            for (boolean value : new boolean[] {true, false}) {
                AsyncTestConfig.Builder builder = AsyncTestConfig.builder();
                Object returned;
                try {
                    returned = method.invoke(builder, value);
                } catch (InvocationTargetException e) {
                    broken.add(method.getName() + "(" + value + ") threw " + e.getCause());
                    continue;
                }
                if (returned != builder) { // NOPMD CompareObjectsWithEquals - the same builder, by identity
                    broken.add(method.getName() + "(" + value + ") returned " + returned);
                }
            }
        }
        assertTrue(setters >= DetectorType.values().length, "found " + setters + " boolean setters,"
                + " fewer than one per detector type; the scan stopped matching");
        assertTrue(broken.isEmpty(), "these setters break a chained builder call: " + broken);
    }
}
