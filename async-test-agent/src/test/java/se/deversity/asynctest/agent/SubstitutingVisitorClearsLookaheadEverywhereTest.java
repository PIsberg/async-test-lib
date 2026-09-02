package se.deversity.asynctest.agent;

import net.bytebuddy.jar.asm.MethodVisitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the substituting visitor overrides every visit method {@code MethodVisitor} declares.
 *
 * <p>The visitor carries a one-instruction lookahead: after it substitutes a call whose result may
 * be discarded, the next {@code POP} is replaced by a hook that takes that result (#454). The flag
 * is meaningful for exactly one instruction, so every other visit method has to clear it before
 * delegating. A method the visitor does not override delegates through {@code MethodVisitor}
 * without clearing anything, and the flag survives into whatever comes next.
 *
 * <p>The failure that guards against is not a wrong finding. A stale flag turns an unrelated
 * {@code POP} into a call whose parameter is a boolean while the value on the stack may be a
 * reference, and that is a {@code VerifyError} in the user's own class at load time, in their
 * build, only when the agent is attached. So the override list is not something to maintain by
 * hand: this test enumerates {@code MethodVisitor} itself, and a new visit method in an ASM
 * upgrade fails here rather than in somebody else's suite.
 */
class SubstitutingVisitorClearsLookaheadEverywhereTest {

    @Test
    @DisplayName("every visit method MethodVisitor declares is overridden by the substituting visitor")
    void everyVisitMethodIsOverridden() throws ClassNotFoundException {
        Class<?> visitor = Class.forName(
                "se.deversity.asynctest.agent.CollectionAccessWeaver$SubstitutingMethodVisitor");

        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Method declared : MethodVisitor.class.getMethods()) {
            if (!declared.getName().startsWith("visit")
                    || Modifier.isStatic(declared.getModifiers())
                    || Modifier.isFinal(declared.getModifiers())
                    || declared.isAnnotationPresent(Deprecated.class)) {
                // The deprecated four-argument visitMethodInsn routes through the five-argument
                // one inside ASM itself, so the override there is the one that matters.
                continue;
            }
            checked++;
            try {
                visitor.getDeclaredMethod(declared.getName(), declared.getParameterTypes());
            } catch (NoSuchMethodException absent) {
                missing.add(declared.getName() + Arrays.toString(declared.getParameterTypes()));
            }
        }

        assertTrue(checked >= 30,
                "MethodVisitor declares about thirty visit methods and this found " + checked
                        + ", so the enumeration is not looking at what it thinks it is");
        assertTrue(missing.isEmpty(),
                "These visit methods are not overridden by SubstitutingMethodVisitor, so they "
                        + "delegate without clearing the discarded-result lookahead. A POP that "
                        + "follows one of them while the flag is stale is rewritten into a hook "
                        + "call with the wrong parameter type on the stack: a VerifyError in the "
                        + "user's class. Override each to clear justSubstituted and delegate: "
                        + missing);
    }
}
