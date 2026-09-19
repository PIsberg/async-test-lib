package se.deversity.asynctest.agent;

import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Defines named modules in layers of their own, from compiled class directories (#668).
 *
 * <p>Two shapes. {@link #fixture()} is {@code com.example.namedfixture} as an explicit module: an
 * automatic one reads every unnamed module and would link to a class-path library whether or not
 * the agent did anything. {@link #library()} is a copy of the library as the automatic module its
 * jar declares, the way it sits on a module path. Descriptors are built in code, so no
 * {@code module-info} has to be compiled into the test sources.
 */
final class NamedFixtureLayer {

    static final String MODULE = "asynctest.namedfixture";

    static final String PACKAGE = "com.example.namedfixture";

    /** The {@code Automatic-Module-Name} async-test-lib's jar declares. */
    static final String LIBRARY_MODULE = "se.deversity.asynctest";

    private NamedFixtureLayer() {
    }

    /** {@return a new layer holding the fixture module, its loader delegating to the test's} */
    static ModuleLayer fixture() {
        return define(ModuleDescriptor.newModule(MODULE).exports(PACKAGE).build(),
                classesOf(NamedFixtureLayer.class));
    }

    /** {@return a new layer holding a copy of the library as an automatic module} */
    static ModuleLayer library() {
        Path root = classesOf(TelemetryRegistry.class);
        if (Files.isRegularFile(root)) {
            // The library resolved as a jar rather than a reactor class directory.
            try {
                root = FileSystems.newFileSystem(root).getPath("/");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Path base = root;
        Set<String> packages;
        try (Stream<Path> files = Files.walk(root)) {
            packages = files.filter(file -> file.toString().endsWith(".class"))
                    .map(file -> base.relativize(file.getParent()).toString().replace('\\', '/').replace('/', '.'))
                    .filter(name -> !name.isEmpty() && !name.startsWith("META-INF"))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return define(ModuleDescriptor.newAutomaticModule(LIBRARY_MODULE).packages(packages).build(), base);
    }

    private static ModuleLayer define(ModuleDescriptor descriptor, Path root) {
        ModuleReference reference = new ModuleReference(descriptor, root.toUri()) {
            @Override
            public ModuleReader open() {
                return new DirectoryReader(root);
            }
        };
        ModuleFinder finder = new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return descriptor.name().equals(name) ? Optional.of(reference) : Optional.empty();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.of(reference);
            }
        };
        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration =
                boot.configuration().resolve(finder, ModuleFinder.of(), Set.of(descriptor.name()));
        return boot.defineModulesWithOneLoader(configuration, NamedFixtureLayer.class.getClassLoader());
    }

    private static Path classesOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serves class files straight from a compiled class directory. */
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
                        .map(file -> root.relativize(file).toString().replace('\\', '/'))
                        .toList().stream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            // Nothing held open.
        }
    }
}
