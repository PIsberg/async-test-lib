package se.deversity.asynctest.agent;

import com.example.agentfixture.MeasuredSpanRecord;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentCollectionHooks;
import se.deversity.asynctest.AgentLockHooks;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Java record must weave, collection substitution included.
 *
 * <p>Every record's {@code equals}/{@code hashCode}/{@code toString} is an {@code invokedynamic}
 * whose bootstrap arguments include one field method handle per component, and a component with a
 * primitive descriptor such as {@code J} is what broke the old substitution path: Byte Buddy's
 * {@code MemberSubstitution} parses those constants and reads a field handle's descriptor as a
 * method descriptor ({@code JavaConstant.MethodHandle.ofAsm}, still present in 1.18.12), so every
 * record in a woven package failed with a {@code StringIndexOutOfBoundsException} and lost all
 * instrumentation. The corpus eval printed 15 such failures per run, one per record on the test
 * classpath. This test fails on that implementation at {@code make()} and pins the replacement:
 * the substituting visitor touches only the invocation kinds it rewrites.
 */
class RecordWeavingRegressionTest {

    @Test
    @DisplayName("a record weaves, its collection calls are substituted, and its indy still works")
    void aRecordWeavesWithBothSubstitutionTables() throws Exception {
        Class<?> woven = new ByteBuddy()
                .redefine(MeasuredSpanRecord.class)
                .visit(CollectionAccessWeaver.substitutions(AgentCollectionHooks.class).get(0))
                .visit(CollectionAccessWeaver.lockSubstitutions(AgentLockHooks.class).get(0))
                // The old implementation threw StringIndexOutOfBoundsException here, while
                // parsing the record's ObjectMethods bootstrap arguments.
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();

        Object span = woven.getConstructor(long.class, String.class).newInstance(42L, "span");
        Object twin = woven.getConstructor(long.class, String.class).newInstance(42L, "span");

        Map<Object, Object> sink = new HashMap<>();
        assertTrue((Boolean) woven.getMethod("noteInto", Map.class).invoke(span, sink),
                "the substituted call must still perform the original operation");
        assertEquals(42L, sink.get("span"), "the put must have stored through the hook");

        // The record's indy-based members must have survived weaving untouched.
        assertEquals(span, twin, "equals is the invokedynamic whose constants broke the old path");
        assertEquals(span.hashCode(), twin.hashCode(), "hashCode rides the same bootstrap");
        assertTrue(span.toString().contains("span"), "toString rides the same bootstrap");
    }
}
