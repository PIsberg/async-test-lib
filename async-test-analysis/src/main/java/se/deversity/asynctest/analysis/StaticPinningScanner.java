package se.deversity.asynctest.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import se.deversity.vibetags.annotations.AICore;

/**
 * Compile-time / class-load-time static bytecode scanner that identifies <em>virtual-thread
 * pinning sites</em> without running any tests.
 *
 * <h2>Problem</h2>
 * {@code VirtualThreadPinningDetector} (in the async-test-lib artifact — deliberately not a
 * {@code @link}, since this module depends on nothing else in the project) finds pinning at
 * runtime by monitoring thread states during stress tests.  If the pinning code path is
 * rarely exercised, it goes undetected.  The runtime detector also requires virtual threads
 * to be actively scheduled and pinned during the observation window.
 *
 * <h2>Approach</h2>
 * Using the <a href="https://asm.ow2.io">ASM</a> bytecode library this scanner walks
 * compiled {@code .class} files and flags any method that:
 * <ol>
 *   <li>Enters a monitor via {@code MONITORENTER}</li>
 *   <li>While inside that monitor, calls a <em>blocking JDK method</em>
 *       (see {@link #BLOCKING_METHODS})</li>
 * </ol>
 * Such methods pin a carrier platform thread when called from a virtual thread, defeating
 * the scalability benefit of Loom.
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Tracks monitor nesting depth only within a single method body; cross-method
 *       synchronization (e.g. a {@code synchronized} method calling a blocking helper)
 *       requires inter-procedural analysis and is not detected.</li>
 *   <li>Exception-handler edges ({@code MONITOREXIT} in finally blocks) may
 *       undercount nesting depth; false negatives are possible but false positives are not.</li>
 * </ul>
 *
 * @since 1.6.0
 */
@AICore(
    sensitivity = "High",
    note = "The whole module is this one class plus ASM, and ArchitectureTest pins both directions: nothing here may reference the library, and asm may not leak out of here. Keep the analysis one-directional — if the scanner starts needing the runner or a detector, that is a design question, not a dependency to add. The asymmetry in the findings is deliberate and must be preserved: monitor depth is tracked within a single method body only, so cross-method synchronization yields false negatives, and MONITOREXIT on exception-handler edges may undercount depth. False negatives are acceptable here; a false positive is not, because the scanner runs without executing tests and has no way to confirm a site."
)
public final class StaticPinningScanner {

    /** A detected pinning site within a compiled class. */
    public record PinningSite(
            String className,
            String methodName,
            String methodDescriptor,
            String blockingOwner,
            String blockingMethod) {

        @Override
        public String toString() {
            return className.replace('/', '.') + "#" + methodName + methodDescriptor
                    + " — calls " + blockingOwner.replace('/', '.') + "." + blockingMethod
                    + "() inside a synchronized block";
        }
    }

    /**
     * Blocking JDK calls that pin a carrier thread when invoked inside a {@code synchronized}
     * block from a virtual thread.  Keyed as {@code "owner/methodName"}.
     */
    private static final Set<String> BLOCKING_METHODS = Set.of(
            // Thread.sleep variants
            "java/lang/Thread/sleep",
            "java/lang/Thread/join",
            // Object monitor wait
            "java/lang/Object/wait",
            // I/O — socket
            "java/net/Socket/connect",
            "java/net/Socket/getInputStream",
            "java/net/Socket/getOutputStream",
            "java/io/InputStream/read",
            "java/io/OutputStream/write",
            // I/O — file (pre-NIO blocking)
            "java/io/FileInputStream/read",
            "java/io/FileOutputStream/write",
            // Selector / channel
            "java/nio/channels/Selector/select",
            "java/nio/channels/Selector/selectNow",
            // Process
            "java/lang/Process/waitFor",
            // Condition.await (ReentrantLock inside synchronized = nested monitor)
            "java/util/concurrent/locks/Condition/await",
            "java/util/concurrent/locks/Condition/awaitNanos",
            "java/util/concurrent/locks/Condition/awaitUntil",
            // BlockingQueue
            "java/util/concurrent/BlockingQueue/take",
            "java/util/concurrent/BlockingQueue/put"
    );

    private StaticPinningScanner() {}

    /**
     * Scans a single {@code .class} file and returns all detected pinning sites.
     *
     * @param classBytes raw bytecode of the compiled class
     * @return unmodifiable list of pinning sites (empty if none found)
     */
    public static List<PinningSite> scanClass(byte[] classBytes) {
        List<PinningSite> results = new ArrayList<>();
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new PinningClassVisitor(results), ClassReader.SKIP_FRAMES);
        return Collections.unmodifiableList(results);
    }

    /**
     * Scans all {@code .class} files reachable from {@code root} (recursively) and
     * returns every detected pinning site.
     *
     * <p>Useful as a post-compile step or as part of a JUnit {@code @BeforeAll} sanity check:
     * <pre>{@code
     * @BeforeAll
     * static void noLoomPinningSites() throws IOException {
     *     Path classes = Path.of("target/classes");
     *     List<PinningSite> sites = StaticPinningScanner.scanDirectory(classes);
     *     assertTrue(sites.isEmpty(), "Pinning sites detected:\n" + sites);
     * }
     * }</pre>
     *
     * @param root directory containing compiled {@code .class} files
     * @return unmodifiable list of all pinning sites found under {@code root}
     * @throws IOException if any {@code .class} file cannot be read
     */
    public static List<PinningSite> scanDirectory(Path root) throws IOException {
        List<PinningSite> results = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (p.toString().endsWith(".class")) {
                    results.addAll(scanClass(Files.readAllBytes(p)));
                }
            }
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * Convenience overload: loads the class bytes from a {@code ClassLoader} by
     * converting the binary name to a resource path and delegates to {@link #scanClass(byte[])}.
     *
     * @param binaryName  e.g. {@code "com.example.MyService"}
     * @param classLoader class loader to load the resource from
     * @throws IOException              if the resource cannot be read
     * @throws IllegalArgumentException if the class resource is not found
     */
    public static List<PinningSite> scanClass(String binaryName, ClassLoader classLoader)
            throws IOException {
        String resourcePath = binaryName.replace('.', '/') + ".class";
        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException(
                        "Class resource not found: " + resourcePath + " in " + classLoader);
            }
            return scanClass(is.readAllBytes());
        }
    }

    // ---- ASM visitors -------------------------------------------------------

    private static final class PinningClassVisitor extends ClassVisitor {

        private final List<PinningSite> results;
        private String className;

        PinningClassVisitor(List<PinningSite> results) {
            super(Opcodes.ASM9);
            this.results = results;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new PinningMethodVisitor(delegate, className, name, descriptor, access, results);
        }
    }

    private static final class PinningMethodVisitor extends MethodVisitor {

        private final String className;
        private final String methodName;
        private final String methodDescriptor;
        private final List<PinningSite> results;

        // Tracks synchronized nesting depth within this method body.
        // Incremented on MONITORENTER, decremented on MONITOREXIT.
        private int monitorDepth = 0;

        // Whether the method is declared `synchronized` (implicit MONITORENTER on entry).
        // We treat it as always inside a monitor; monitorDepth is not adjusted for it
        // because the MONITORENTER/MONITOREXIT bytecodes are not emitted for ACC_SYNCHRONIZED.
        private final boolean isSynchronizedMethod;

        PinningMethodVisitor(MethodVisitor delegate, String className, String methodName,
                              String methodDescriptor, int access, List<PinningSite> results) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
            this.methodName = methodName;
            this.methodDescriptor = methodDescriptor;
            this.results = results;
            this.isSynchronizedMethod = (access & Opcodes.ACC_SYNCHRONIZED) != 0;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.MONITORENTER) {
                monitorDepth++;
            } else if (opcode == Opcodes.MONITOREXIT) {
                if (monitorDepth > 0) monitorDepth--;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                     String descriptor, boolean isInterface) {
            if ((monitorDepth > 0 || isSynchronizedMethod)
                    && isBlockingMethod(owner, name)) {
                results.add(new PinningSite(className, methodName, methodDescriptor, owner, name));
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        private static boolean isBlockingMethod(String owner, String methodName) {
            return BLOCKING_METHODS.contains(owner + "/" + methodName);
        }
    }
}
