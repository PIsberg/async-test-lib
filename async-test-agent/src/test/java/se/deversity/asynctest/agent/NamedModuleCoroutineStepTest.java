package se.deversity.asynctest.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a coroutine step is marked where kotlin stdlib is a named module (#1468).
 *
 * <p>On a module path the stdlib is the explicit module {@code kotlin.stdlib}: it reads only what it
 * requires, so the woven {@code resumeWith} cannot link to the library without a read edge, and its
 * {@code kotlin.coroutines.jvm.internal} is exported but not open, so the hook cannot open the
 * protected {@code invokeSuspend} it calls through a handle. The agent left the class alone there,
 * and a lost update between two coroutines on one thread went unreported.
 *
 * <p>The agent's tests have no Kotlin, so the module is built here with the shape the stdlib
 * compiles to, as in {@link CoroutineStepWeaverTest}: {@code Continuation}, {@code
 * BaseContinuationImpl} and one step whose {@code invokeSuspend} returns its own stack, which
 * shows whether {@code resumeWith} ran it through the hook. Its own class, since {@code selfAttach}
 * is at-most-once per JVM; {@code reuseForks=false} gives it its own fork.
 */
@Tag("e2e")
class NamedModuleCoroutineStepTest {

    private static final String STDLIB = "kotlin.stdlib";
    private static final String INTERNAL = "kotlin.coroutines.jvm.internal";
    private static final String BASE = "kotlin/coroutines/jvm/internal/BaseContinuationImpl";
    private static final String STEP = "kotlin/coroutines/jvm/internal/StackStep";
    private static final String CONTINUATION = "kotlin/coroutines/Continuation";
    private static final String STEP_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";

    private static Class<?> step;

    @BeforeAll
    static void attachThenLoadTheStdlibModule() throws Exception {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported, "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        AsyncTestAgent.selfAttach("includes=" + NamedFixtureLayer.PACKAGE + ",fields=true");

        ModuleDescriptor descriptor = ModuleDescriptor.newModule(STDLIB)
                .exports("kotlin.coroutines").exports(INTERNAL).build();
        ModuleLayer layer = NamedFixtureLayer.module(descriptor, stdlibClasses());
        step = Class.forName(STEP.replace('/', '.'), true, layer.findLoader(STDLIB));
    }

    @Test
    @DisplayName("resumeWith in a named kotlin.stdlib runs its step through the coroutine hook")
    void aStepInANamedStdlibIsMarked() throws Exception {
        assertTrue(step.getModule().isNamed(), "the stdlib must be a named module, or this proves nothing");
        assertEquals(STDLIB, step.getModule().getName());

        Object stack = step.getMethod("resumeWith", Object.class)
                .invoke(step.getConstructor().newInstance(), (Object) null);

        assertTrue(Arrays.stream((StackTraceElement[]) stack).anyMatch(frame ->
                        "se.deversity.asynctest.AgentCoroutineHooks".equals(frame.getClassName())
                                && "coroutineStep".equals(frame.getMethodName())),
                "resumeWith called invokeSuspend directly: the step was not marked, so a lost update "
                        + "between coroutines on one thread is not reported (#1468)");
    }

    @Test
    @DisplayName("the stdlib's internal package is opened to the library alone, and reads it")
    void theInternalPackageIsOpenedToTheLibraryOnly() {
        Module stdlib = step.getModule();
        Module library = TelemetryRegistry.class.getModule();
        assertTrue(stdlib.canRead(library), "the woven resumeWith links to the library");
        assertTrue(stdlib.isOpen(INTERNAL, library), "the hook opens invokeSuspend from the library");
        assertFalse(stdlib.isOpen(INTERNAL), "the package must not be opened to every module");
        assertFalse(stdlib.isOpen("kotlin.coroutines", library), "only the package holding invokeSuspend");
    }

    @Test
    @DisplayName("a named stdlib whose loader cannot reach the hook is left unwoven; an unnamed one needs nothing")
    void aStdlibTheHookCannotReachIsRefused() {
        java.lang.instrument.Instrumentation inst = ByteBuddyAgent.getInstrumentation();
        Module stdlib = step.getModule();
        ClassLoader isolated = new ClassLoader(null) { };
        assertFalse(UpdaterAccess.openCoroutineSteps(inst, isolated, stdlib),
                "no hook to call: woven, every coroutine would fail in resumeWith");
        assertFalse(UpdaterAccess.openCoroutineSteps(inst, null, stdlib), "the bootstrap loader resolves no hook");
        assertTrue(UpdaterAccess.openCoroutineSteps(inst, isolated, isolated.getUnnamedModule()),
                "an unnamed module reads every module and is open to them");
        assertTrue(UpdaterAccess.openCoroutineSteps(inst, isolated, null), "an unknown module is woven as before");
    }

    /** Writes the stdlib-shaped classes to a directory of their own. */
    private static Path stdlibClasses() throws IOException {
        Path root = Files.createTempDirectory("named-kotlin-stdlib");
        root.toFile().deleteOnExit();
        write(root, CONTINUATION, continuation());
        write(root, BASE, base());
        write(root, STEP, stackStep());
        return root;
    }

    private static void write(Path root, String name, byte[] bytes) throws IOException {
        Path file = root.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static byte[] continuation() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                CONTINUATION, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code resumeWith} returns what {@code invokeSuspend} returned, so the test can read it. */
    private static byte[] base() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, BASE, null, "java/lang/Object",
                new String[] {CONTINUATION});
        constructor(cw, "java/lang/Object");
        cw.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "invokeSuspend", STEP_DESCRIPTOR,
                null, null).visitEnd();
        MethodVisitor resume = cw.visitMethod(Opcodes.ACC_PUBLIC, "resumeWith", STEP_DESCRIPTOR, null, null);
        resume.visitCode();
        resume.visitVarInsn(Opcodes.ALOAD, 0);
        resume.visitVarInsn(Opcodes.ALOAD, 1);
        resume.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BASE, "invokeSuspend", STEP_DESCRIPTOR, false);
        resume.visitInsn(Opcodes.ARETURN);
        resume.visitMaxs(0, 0);
        resume.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A step whose body returns the stack it runs on. */
    private static byte[] stackStep() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, STEP, null, BASE, null);
        constructor(cw, BASE);
        MethodVisitor body = cw.visitMethod(Opcodes.ACC_PROTECTED, "invokeSuspend", STEP_DESCRIPTOR, null, null);
        body.visitCode();
        body.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false);
        body.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getStackTrace",
                "()[Ljava/lang/StackTraceElement;", false);
        body.visitInsn(Opcodes.ARETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }
}
