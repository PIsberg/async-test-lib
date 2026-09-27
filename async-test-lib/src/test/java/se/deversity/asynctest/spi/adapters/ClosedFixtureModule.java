package se.deversity.asynctest.spi.adapters;

import se.deversity.asynctest.spi.adapters.fixture.HiddenReportDetector;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads the {@code spi.adapters.fixture} detectors into a named module that exports their package
 * and opens it to nobody (#851), which is what the adapter meets when a third-party detector sits
 * in such a module.
 *
 * <p>On the class path the fixtures are in the unnamed module, which is open to every module, so
 * {@code trySetAccessible} always succeeds there and could not show the other outcome. The
 * descriptor is built in code, as {@code async-test-agent}'s {@code NamedFixtureLayer} does, so no
 * {@code module-info} is compiled into the test sources. The module reads the test's unnamed
 * module, where the library sits, so a fixture that builds a {@code Violation} links.
 */
final class ClosedFixtureModule {

    private static final String MODULE = "asynctest.closedfixture";

    private ClosedFixtureModule() {
    }

    /**
     * {@return a new instance of {@code fixture}'s copy in a fresh closed module}
     *
     * @param fixture a public class with a public no-argument constructor in the fixture package;
     *                the returned object's class has the same name but is a different class
     */
    static Object newInstance(Class<?> fixture) {
        try {
            return load(fixture).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> load(Class<?> fixture) throws ClassNotFoundException {
        String pkg = HiddenReportDetector.class.getPackageName();
        ModuleDescriptor descriptor = ModuleDescriptor.newModule(MODULE).exports(pkg).build();
        Path root = classesOf(fixture);
        ModuleReference reference = new ModuleReference(descriptor, root.toUri()) {
            @Override
            public ModuleReader open() {
                return new DirectoryReader(root);
            }
        };
        ModuleFinder finder = new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return MODULE.equals(name) ? Optional.of(reference) : Optional.empty();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.of(reference);
            }
        };
        ModuleLayer boot = ModuleLayer.boot();
        ClassLoader parent = ClosedFixtureModule.class.getClassLoader();
        Configuration configuration = boot.configuration().resolve(finder, ModuleFinder.of(), Set.of(MODULE));
        ModuleLayer.Controller controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(boot), parent);
        Module module = controller.layer().findModule(MODULE).orElseThrow();
        controller.addReads(module, parent.getUnnamedModule());
        Class<?> loaded = controller.layer().findLoader(MODULE).loadClass(fixture.getName());
        if (loaded.getModule() != module) {
            throw new IllegalStateException(fixture + " did not load into " + MODULE + ": " + loaded.getModule());
        }
        return loaded;
    }

    private static Path classesOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serves class files straight from the test's compiled class directory. */
    private static final class DirectoryReader implements ModuleReader {

        private final Path root;

        DirectoryReader(Path root) {
            this.root = root;
        }

        @Override
        public Optional<URI> find(String name) {
            Path file = root.resolve(name);
            return Files.isRegularFile(file) ? Optional.of(file.toUri()) : Optional.empty();
        }

        @Override
        public Stream<String> list() {
            try (Stream<Path> files = Files.walk(root)) {
                return files.filter(Files::isRegularFile)
                        .map(file -> root.relativize(file).toString().replace(File.separatorChar, '/'))
                        .toList().stream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            // nothing held open
        }
    }
}
