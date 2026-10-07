package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DetectorRegistry} builds exactly the detectors {@link AsyncTestConfig#enabledDetectors()}
 * names, one per {@link DetectorType}, from its factory table (#916).
 *
 * <p>The registry used to decide each construction from a config boolean of its own,
 * {@code cfg.detectXxx ? new Xxx() : null}, 146 times: a construction keyed on the wrong flag
 * compiled and built a detector nobody asked for while skipping the one they did. Keyed on the
 * type, the registry has nothing per detector left to pair with a flag, and the instances it
 * built can be compared with the set that asked for them.
 */
class DetectorRegistryFactoryTableTest {

    @Test
    void everyTypeHasOneFactory_andDetectAllBuildsThemAll() {
        DetectorRegistry registry = new DetectorRegistry(AsyncTestConfig.builder().detectAll(true).build());
        assertEquals(EnumSet.allOf(DetectorType.class), registry.instances().keySet(),
                "every DetectorType needs exactly one factory in the table");
        Set<Object> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(registry.instances().values());
        assertEquals(DetectorType.values().length, distinct.size(), "one instance per type, none shared");
    }

    @Test
    void theRegistryBuildsExactlyTheEnabledSet_andItsFieldsAreThoseInstances() throws IllegalAccessException {
        Random random = new Random(916);
        DetectorType[] all = DetectorType.values();
        for (int round = 0; round < 50; round++) {
            List<DetectorType> includes = new ArrayList<>();
            for (DetectorType t : all) {
                if (random.nextInt(4) == 0) includes.add(t);
            }
            if (includes.isEmpty()) includes.add(DetectorType.TIMER);
            AsyncTestConfig cfg = AsyncTestConfig.builder()
                    .includes(includes.toArray(new DetectorType[0])).build();
            DetectorRegistry registry = new DetectorRegistry(cfg);

            assertEquals(cfg.enabledDetectors(), registry.instances().keySet(), "round " + round);
            List<Object> fieldValues = detectorFieldValues(registry);
            assertEquals(registry.instances().size(), fieldValues.size(),
                    "each built detector sits in exactly one registry field, round " + round);
            Set<Object> built = Collections.newSetFromMap(new IdentityHashMap<>());
            built.addAll(registry.instances().values());
            for (Object value : fieldValues) {
                assertTrue(built.contains(value), value.getClass().getSimpleName()
                        + " is in a registry field but was not built from the table");
            }
        }
    }

    @Test
    void eachRegistryBuildsFreshInstances() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        Map<DetectorType, Object> first = new DetectorRegistry(cfg).instances();
        Map<DetectorType, Object> second = new DetectorRegistry(cfg).instances();
        for (DetectorType t : DetectorType.values()) {
            assertNotSame(first.get(t), second.get(t), t + " must not be shared between runs");
        }
    }

    /** {@return the non-null values of the registry's final detector fields} */
    private static List<Object> detectorFieldValues(DetectorRegistry registry) throws IllegalAccessException {
        List<Object> values = new ArrayList<>();
        for (Field f : DetectorRegistry.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (Modifier.isStatic(mods) || !Modifier.isFinal(mods)
                    || !f.getType().getPackageName().endsWith(".diagnostics")) {
                continue;
            }
            f.setAccessible(true);
            Object value = f.get(registry);
            if (value != null) values.add(value);
        }
        return values;
    }
}
