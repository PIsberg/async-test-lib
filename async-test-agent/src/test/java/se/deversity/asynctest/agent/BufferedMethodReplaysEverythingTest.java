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
 * Pins that the buffering visitor overrides every visit method {@code MethodVisitor} declares.
 *
 * <p>Recognising a loop that encloses a wait in another method needs the whole class before any of
 * it is emitted, so the monitor wrapper buffers each method as a list of replayable actions and
 * plays it into the substituting visitor once the wait-helpers are known (#707). The buffer has no
 * delegate: a visit method it does not override is recorded nowhere and replayed to nobody, so the
 * instruction is simply gone from the woven class.
 *
 * <p>That failure is silent in the worst way. Dropping a jump, a frame or a try/catch entry does
 * not fail weaving; it produces a class that verifies differently or executes differently inside
 * somebody else's test suite, only when the agent is attached. Like
 * {@link SubstitutingVisitorClearsLookaheadEverywhereTest}, this enumerates {@code MethodVisitor}
 * rather than trusting a hand-kept list, so a visit method added by an ASM upgrade fails here.
 */
class BufferedMethodReplaysEverythingTest {

    @Test
    @DisplayName("every visit method MethodVisitor declares is buffered for replay")
    void everyVisitMethodIsBuffered() throws ClassNotFoundException {
        Class<?> buffer = Class.forName(
                "se.deversity.asynctest.agent.CollectionAccessWeaver$BufferedMethod");

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
                buffer.getDeclaredMethod(declared.getName(), declared.getParameterTypes());
            } catch (NoSuchMethodException absent) {
                missing.add(declared.getName() + Arrays.toString(declared.getParameterTypes()));
            }
        }

        assertTrue(checked >= 30,
                "MethodVisitor declares about thirty visit methods and this found " + checked
                        + ", so the enumeration is not looking at what it thinks it is");
        assertTrue(missing.isEmpty(),
                "These visit methods are not buffered by BufferedMethod. It has no delegate, so "
                        + "what they were given is dropped rather than replayed, and the woven "
                        + "class silently loses those instructions in the user's own build. "
                        + "Override each to record an action and replay it: " + missing);
    }
}
