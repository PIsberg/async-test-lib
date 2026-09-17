package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LibraryBuild} artifact inspection and staleness detection.
 */
class LibraryBuildTest {

    @Test
    @DisplayName("complaintAbout: returns empty when jar path is empty")
    void complaintAboutReturnsEmptyWhenJarIsEmpty(@TempDir Path tempDir) {
        Optional<String> complaint = LibraryBuild.complaintAbout(Optional.empty(), tempDir, "library");
        assertTrue(complaint.isEmpty());
    }

    @Test
    @DisplayName("complaintAbout: returns empty when jar is a directory")
    void complaintAboutReturnsEmptyWhenJarIsDirectory(@TempDir Path tempDir) {
        Optional<String> complaint = LibraryBuild.complaintAbout(Optional.of(tempDir), tempDir, "library");
        assertTrue(complaint.isEmpty());
    }

    @Test
    @DisplayName("complaintAbout: returns empty when classes directory does not exist")
    void complaintAboutReturnsEmptyWhenClassesDirectoryDoesNotExist(@TempDir Path tempDir) throws IOException {
        Path jar = tempDir.resolve("lib.jar");
        Files.writeString(jar, "fake jar");
        Path nonExistentClasses = tempDir.resolve("no-such-classes");

        Optional<String> complaint = LibraryBuild.complaintAbout(Optional.of(jar), nonExistentClasses, "library");
        assertTrue(complaint.isEmpty());
    }

    @Test
    @DisplayName("complaintAbout: returns empty when classes directory has no .class files")
    void complaintAboutReturnsEmptyWhenClassesDirectoryHasNoClassFiles(@TempDir Path tempDir) throws IOException {
        Path jar = tempDir.resolve("lib.jar");
        Files.writeString(jar, "fake jar");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        Files.writeString(classesDir.resolve("README.txt"), "no classes here");

        Optional<String> complaint = LibraryBuild.complaintAbout(Optional.of(jar), classesDir, "library");
        assertTrue(complaint.isEmpty());
    }

    @Test
    @DisplayName("complaintAbout: returns empty when compiled classes are older than or equal to installed jar")
    void complaintAboutReturnsEmptyWhenCompiledIsOlderOrEqual(@TempDir Path tempDir) throws IOException {
        Path jar = tempDir.resolve("lib.jar");
        Files.writeString(jar, "fake jar");
        Files.setLastModifiedTime(jar, FileTime.from(Instant.ofEpochMilli(5000L)));

        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        Path classFile = classesDir.resolve("Foo.class");
        Files.writeString(classFile, "bytecode");
        Files.setLastModifiedTime(classFile, FileTime.from(Instant.ofEpochMilli(4000L)));

        Optional<String> complaint = LibraryBuild.complaintAbout(Optional.of(jar), classesDir, "library");
        assertTrue(complaint.isEmpty());

        // Exactly equal timestamps also consider the build up-to-date
        Files.setLastModifiedTime(classFile, FileTime.from(Instant.ofEpochMilli(5000L)));
        assertTrue(LibraryBuild.complaintAbout(Optional.of(jar), classesDir, "library").isEmpty());
    }

    @Test
    @DisplayName("complaintAbout: returns formatted warning when compiled classes are newer than installed jar")
    void complaintAboutReturnsFormattedComplaintWhenCompiledIsNewer(@TempDir Path tempDir) throws IOException {
        Path jar = tempDir.resolve("async-test-agent-1.12.0.jar");
        Files.writeString(jar, "fake jar");
        Files.setLastModifiedTime(jar, FileTime.from(Instant.ofEpochMilli(1000L)));

        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        Path classFile = classesDir.resolve("Bar.class");
        Files.writeString(classFile, "bytecode");
        Files.setLastModifiedTime(classFile, FileTime.from(Instant.ofEpochMilli(2000L)));

        Optional<String> complaint = LibraryBuild.complaintAbout(Optional.of(jar), classesDir, "agent");
        assertTrue(complaint.isPresent());
        String text = complaint.get();
        assertTrue(text.contains("the agent resolved from the local repository (async-test-agent-1.12.0.jar, 1000)"));
        assertTrue(text.contains("is older than the working tree's compiled classes (2000)"));
        assertTrue(text.contains("mvn install -DskipTests -Djacoco.skip=true"));
    }

    @Test
    @DisplayName("digestOf: computes 12-char lowercase hex SHA-256 prefix for file content")
    void digestOfComputesExpected12CharPrefix(@TempDir Path tempDir) throws IOException, NoSuchAlgorithmException {
        Path file = tempDir.resolve("test.bin");
        byte[] content = "digest-test-content\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        String digest = LibraryBuild.digestOf(file);
        assertNotNull(digest);
        assertEquals(12, digest.length());

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(sha256.digest(content)).substring(0, 12);
        assertEquals(expected, digest);
    }

    @Test
    @DisplayName("newestClassIn: recursively traverses directory tree and returns newest .class timestamp")
    void newestClassInTraversesTreeAndFiltersClassFiles(@TempDir Path tempDir) throws IOException {
        Path subDir = tempDir.resolve("sub/pkg");
        Files.createDirectories(subDir);

        // Non-.class files must be ignored even if newer
        Path txtFile = subDir.resolve("notes.txt");
        Files.writeString(txtFile, "notes");
        Files.setLastModifiedTime(txtFile, FileTime.from(Instant.ofEpochMilli(9000L)));

        Path classA = tempDir.resolve("ClassA.class");
        Files.writeString(classA, "classA");
        Files.setLastModifiedTime(classA, FileTime.from(Instant.ofEpochMilli(3000L)));

        Path classB = subDir.resolve("ClassB.class");
        Files.writeString(classB, "classB");
        Files.setLastModifiedTime(classB, FileTime.from(Instant.ofEpochMilli(7000L)));

        long newest = LibraryBuild.newestClassIn(tempDir);
        assertEquals(7000L, newest);
    }

    @Test
    @DisplayName("newestClassIn: returns 0 when no .class files exist")
    void newestClassInReturnsZeroWhenNoClassFiles(@TempDir Path tempDir) throws IOException {
        Path subDir = tempDir.resolve("sub");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("notes.txt"), "notes");

        long newest = LibraryBuild.newestClassIn(tempDir);
        assertEquals(0L, newest);
    }

    @Test
    @DisplayName("describe: formats report line correctly")
    void describeFormat() {
        String description = LibraryBuild.describe();
        assertNotNull(description);
        assertTrue(description.startsWith("- Library under test: "));
        assertFalse(description.contains("could not be located on the classpath"));
    }
}
