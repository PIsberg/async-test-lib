package se.deversity.asynctest.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * An {@link Instrumentation} that refuses one class and narrows the loaded-class list.
 *
 * <p>Everything delegates to the real handle, so a batch that is allowed through is genuinely
 * re-woven by the JVM and the test can assert on telemetry rather than on bookkeeping. Two methods
 * do not delegate: {@link #getAllLoadedClasses()} answers a fixed pair, so the retransformation
 * pass is bounded and fast instead of sweeping the whole test JVM, and
 * {@link #retransformClasses(Class[])} throws for any batch containing the poisoned class, which
 * is how the JVM behaves for a class it cannot re-verify.
 *
 * @see RetransformBatchIsolationTest
 */
final class PoisoningInstrumentation implements Instrumentation {

    private final Instrumentation delegate;
    private final Class<?> poison;
    private final Class<?>[] loaded;
    private final List<Class<?>> retransformed = Collections.synchronizedList(new ArrayList<>());
    private volatile int refusedBatches;

    PoisoningInstrumentation(Instrumentation delegate, Class<?> poison, List<Class<?>> loaded) {
        this.delegate = delegate;
        this.poison = poison;
        this.loaded = loaded.toArray(new Class<?>[0]);
    }

    /** {@return the classes the JVM actually re-wove} */
    List<Class<?>> retransformed() {
        return List.copyOf(retransformed);
    }

    /** {@return how many batches were refused, so a test can check its own premise} */
    int refusedBatches() {
        return refusedBatches;
    }

    @Override
    public void retransformClasses(Class<?>... classes) throws UnmodifiableClassException {
        for (Class<?> candidate : classes) {
            if (candidate == poison) {
                refusedBatches++;
                throw new UnmodifiableClassException(
                        "simulated: class redefinition failed for " + candidate.getName());
            }
        }
        delegate.retransformClasses(classes);
        retransformed.addAll(List.of(classes));
    }

    @Override
    public Class<?>[] getAllLoadedClasses() {
        return loaded.clone();
    }

    @Override
    public void addTransformer(ClassFileTransformer transformer, boolean canRetransform) {
        delegate.addTransformer(transformer, canRetransform);
    }

    @Override
    public void addTransformer(ClassFileTransformer transformer) {
        delegate.addTransformer(transformer);
    }

    @Override
    public boolean removeTransformer(ClassFileTransformer transformer) {
        return delegate.removeTransformer(transformer);
    }

    @Override
    public boolean isRetransformClassesSupported() {
        return delegate.isRetransformClassesSupported();
    }

    @Override
    public boolean isRedefineClassesSupported() {
        return delegate.isRedefineClassesSupported();
    }

    @Override
    public void redefineClasses(ClassDefinition... definitions) throws UnmodifiableClassException,
            ClassNotFoundException {
        delegate.redefineClasses(definitions);
    }

    @Override
    public boolean isModifiableClass(Class<?> type) {
        return delegate.isModifiableClass(type);
    }

    @Override
    public Class<?>[] getInitiatedClasses(ClassLoader loader) {
        return delegate.getInitiatedClasses(loader);
    }

    @Override
    public long getObjectSize(Object objectToSize) {
        return delegate.getObjectSize(objectToSize);
    }

    @Override
    public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {
        delegate.appendToBootstrapClassLoaderSearch(jarfile);
    }

    @Override
    public void appendToSystemClassLoaderSearch(JarFile jarfile) {
        delegate.appendToSystemClassLoaderSearch(jarfile);
    }

    @Override
    public boolean isNativeMethodPrefixSupported() {
        return delegate.isNativeMethodPrefixSupported();
    }

    @Override
    public void setNativeMethodPrefix(ClassFileTransformer transformer, String prefix) {
        delegate.setNativeMethodPrefix(transformer, prefix);
    }

    @Override
    public boolean isModifiableModule(Module module) {
        return delegate.isModifiableModule(module);
    }

    @Override
    public void redefineModule(Module module, Set<Module> extraReads,
                               Map<String, Set<Module>> extraExports,
                               Map<String, Set<Module>> extraOpens,
                               Set<Class<?>> extraUses,
                               Map<Class<?>, List<Class<?>>> extraProvides) {
        delegate.redefineModule(module, extraReads, extraExports, extraOpens, extraUses,
                extraProvides);
    }
}
