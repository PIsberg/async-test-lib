package com.example.corpus;

import se.deversity.asynctest.DetectorType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Which build of the library produced a report, and whether it is the current one.
 *
 * <p><strong>The incident this exists for.</strong> Wave 7 of the recording lane opened with a
 * row that had been green for four waves suddenly failing. The new rows were not the cause:
 * {@code ~/.m2} held an {@code async-test-lib:1.11.0} jar built before a detector fix, while the
 * working tree held one built after. Same version number, different bytes. This module resolves
 * the library from the local repository rather than the reactor - deliberately, since it sits
 * outside the reactor - so a stale install silently changes every number in every report.
 *
 * <p>That failure was loud only by luck. A pinned pair caught it. A change to a detector with no
 * pair would have moved the headline rates with nothing going red at all, and the report would
 * have been published with numbers from a build nobody could identify afterwards (#425).
 *
 * <p>So two things. Every report names the jar it measured, with a digest, which makes a number
 * traceable to bytes rather than to a version string. And {@link #stalenessComplaint()} compares
 * that jar against the working tree's compiled classes, so the common case - forgetting
 * {@code mvn install} after changing the library - fails loudly instead of quietly.
 */
final class LibraryBuild {

    /**
     * Where the working tree's freshly compiled library classes live.
     *
     * <p>Relative, because Surefire runs this module with its own directory as the working
     * directory. Absent when the corpus is run against a released artifact rather than from a
     * checkout, which is a legitimate configuration and reports as unchecked rather than as
     * passed.
     */
    private static final Path WORKING_TREE_CLASSES =
            Path.of("..", "async-test-lib", "target", "classes");

    /**
     * The agent's compiled classes, checked for the same staleness as the library's.
     *
     * <p>Added after the library check went in and immediately missed a case it should have
     * caught. The agent and the library are separate artifacts that call into each other by name:
     * the weaver names hook methods the library declares, so a new agent against an old library
     * substitutes a call site with a method that is not there. The symptom is not an error - the
     * substitution simply produces nothing, and a lane that had been detecting reports an empty
     * findings list. That is the same defect as a stale library, arriving from the other side.
     */
    private static final Path AGENT_TREE_CLASSES =
            Path.of("..", "async-test-agent", "target", "classes");

    private LibraryBuild() {
    }

    /** {@return a report line naming the library artifact these numbers came from} */
    static String describe() {
        Optional<Path> jar = resolvedArtifact();
        if (jar.isEmpty()) {
            return "- Library under test: could not be located on the classpath";
        }
        Path path = jar.get();
        String detail = Files.isDirectory(path)
                ? "a compiled class directory, so these numbers are the working tree's"
                : "sha256:" + digestOf(path);
        return "- Library under test: " + path.getFileName() + " (" + detail + ")";
    }

    /**
     * {@return why the resolved library is out of date, or empty when it is not}
     *
     * <p>Compares modification times rather than contents. A content comparison would have to
     * unpack the jar and know which classes matter, and the question here is narrower than that:
     * has the library been rebuilt since it was last installed. A timestamp answers it, and
     * answers it the same way whether one class changed or a hundred.
     */
    static Optional<String> stalenessComplaint() {
        Optional<String> library = complaintAbout(resolvedArtifact(), WORKING_TREE_CLASSES,
                "library");
        if (library.isPresent()) {
            return library;
        }
        return complaintAbout(resolvedAgentJar(), AGENT_TREE_CLASSES, "agent");
    }

    /**
     * {@return why {@code jar} is out of date against {@code classes}, or empty when it is not}
     *
     * @param jar     the resolved artifact, if there is one
     * @param classes the working tree's compiled output for it
     * @param what    what to call the artifact in the failure message
     */
    private static Optional<String> complaintAbout(Optional<Path> jar, Path classes, String what) {
        if (jar.isEmpty() || Files.isDirectory(jar.get()) || !Files.isDirectory(classes)) {
            return Optional.empty();
        }
        long installed = lastModified(jar.get());
        long compiled = newestClassIn(classes);
        if (compiled <= installed) {
            return Optional.empty();
        }
        return Optional.of("the " + what + " resolved from the local repository ("
                + jar.get().getFileName() + ", " + installed + ") is older than the working "
                + "tree's compiled classes (" + compiled + "). Every number in every report "
                + "would come from the older build while the source says otherwise, and a "
                + "change with no corpus pair would move the headline rates with "
                + "nothing going red. Run: mvn install -DskipTests -Djacoco.skip=true");
    }

    /**
     * {@return the agent jar this run attached, if it attached one}
     *
     * <p>Read from the {@code -javaagent} argument rather than from a class, because the agent's
     * own classes are not on this module's compile classpath - which is the architectural rule
     * that keeps byte-buddy and asm out of the library, and is worth not breaking for a
     * bookkeeping check.
     */
    private static Optional<Path> resolvedAgentJar() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                .stream()
                .filter(argument -> argument.startsWith("-javaagent:"))
                .map(argument -> argument.substring("-javaagent:".length()))
                .map(value -> value.contains("=") ? value.substring(0, value.indexOf('=')) : value)
                .filter(value -> value.contains("async-test-agent"))
                .map(Path::of)
                .filter(Files::isRegularFile)
                .findFirst();
    }

    /** {@return the jar or class directory the library was loaded from} */
    private static Optional<Path> resolvedArtifact() {
        try {
            return Optional.ofNullable(DetectorType.class.getProtectionDomain().getCodeSource())
                    .map(source -> source.getLocation())
                    .map(location -> {
                        try {
                            return Path.of(location.toURI());
                        } catch (URISyntaxException e) {
                            throw new IllegalStateException("unreadable code source " + location, e);
                        }
                    });
        } catch (SecurityException e) {
            return Optional.empty();
        }
    }

    private static String digestOf(Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                sha256.update(buffer, 0, read);
            }
            // Twelve hex characters: enough to tell two builds apart in a report header, short
            // enough to read. This identifies a build, it does not authenticate one.
            return HexFormat.of().formatHex(sha256.digest()).substring(0, 12);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not digest " + jar, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the timestamp of " + path, e);
        }
    }

    /** {@return the newest modification time among the class files under {@code root}} */
    private static long newestClassIn(Path root) {
        long[] newest = {0L};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (file.toString().endsWith(".class")) {
                        newest[0] = Math.max(newest[0], attributes.lastModifiedTime().toMillis());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
        return newest[0];
    }
}
