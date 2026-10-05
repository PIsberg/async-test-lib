package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Races {@link LicenseValidationCache#record} across forked JVMs, the way a licensed build's
 * surefire forks reach it.
 *
 * <p>Why this exists: the temporary file {@code record} writes before its atomic move used to be
 * named after the thread id. Inside one JVM that is unique, so the in-JVM
 * {@code LicenseValidationCacheDogfoodTest} cannot see the difference. Across JVMs it is not: the
 * main thread of every fork has the same id, so forks recording the same validation at once
 * shared one temporary file, and one could move the other's half-written content into place. A
 * record read back that way is "not fresh", which costs an online revalidation, and the outage
 * grace path loses the record it relies on.
 *
 * <p>{@link #FORKS} child JVMs run {@link Writer} on one hash in one directory. They wait on a go
 * file so their loops overlap rather than being staggered by JVM startup, then record and read
 * back {@link #ITERATIONS} times each. Every read-back must be fresh, and the directory must end
 * holding the one record and nothing else.
 */
class LicenseValidationCacheCrossJvmTest {

    private static final int FORKS = 4;
    private static final int ITERATIONS = 400;
    private static final String HASH = LicenseValidationCache.hash("cross-jvm-writers");

    @TempDir
    Path cacheDir;

    @TempDir
    Path signalDir;

    @Test
    void forksRecordingTheSameValidationNeverReadAPartialRecord() throws Exception {
        Path go = signalDir.resolve("go");
        List<Process> children = new ArrayList<>();
        List<BufferedReader> outputs = new ArrayList<>();
        try {
            for (int i = 0; i < FORKS; i++) {
                Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dlicense.cache.dir=" + cacheDir,
                        "-Dlicense.cache.ttl.hours=24",
                        "-cp", System.getProperty("java.class.path"),
                        Writer.class.getName(), HASH, go.toString(), Integer.toString(ITERATIONS))
                        .redirectErrorStream(true)
                        .start();
                children.add(child);
                outputs.add(new BufferedReader(
                        new InputStreamReader(child.getInputStream(), Charset.defaultCharset())));
            }
            for (BufferedReader output : outputs) {
                awaitReady(output);
            }
            Files.createFile(go);

            for (int i = 0; i < FORKS; i++) {
                Process child = children.get(i);
                String rest = outputs.get(i).lines().reduce("", (a, b) -> a + b + "\n");
                assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child JVM did not exit within 60s:\n" + rest);
                assertEquals(0, child.exitValue(), "child JVM failed:\n" + rest);
                assertEquals(List.of(Writer.RESULT + 0),
                        rest.lines().filter(line -> line.startsWith(Writer.RESULT)).toList(),
                        "a fork read back a record that was missing or partial while other forks "
                                + "rewrote it, so a licensed build would revalidate online:\n" + rest);
            }
        } finally {
            children.forEach(Process::destroyForcibly);
        }

        List<String> files;
        try (Stream<Path> listing = Files.list(cacheDir)) {
            files = listing.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("validation-" + HASH + ".ok"), files,
                files.size() + " files where one record belongs: forks left temporary files behind");
    }

    /** Reads past JVM banner lines, such as "Picked up JAVA_TOOL_OPTIONS", to the ready line. */
    private static void awaitReady(BufferedReader output) throws IOException {
        StringBuilder seen = new StringBuilder();
        for (String line = output.readLine(); line != null; line = output.readLine()) {
            if (line.equals(Writer.READY)) {
                return;
            }
            seen.append(line).append('\n');
        }
        throw new AssertionError("a child JVM exited before it was ready:\n" + seen);
    }

    /** The child JVM's main class: records and reads back one hash, counting stale read-backs. */
    static final class Writer {

        static final String READY = "ready";
        static final String RESULT = "notFresh=";

        private Writer() {
        }

        public static void main(String[] args) throws IOException, InterruptedException {
            String hash = args[0];
            Path go = Path.of(args[1]);
            int iterations = Integer.parseInt(args[2]);

            System.out.println(READY);
            System.out.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(go)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("the go signal never came");
                }
                Thread.sleep(1);
            }

            int notFresh = 0;
            for (int i = 0; i < iterations; i++) {
                LicenseValidationCache.record(hash);
                if (!LicenseValidationCache.isFresh(hash)) {
                    notFresh++;
                }
            }
            System.out.println(RESULT + notFresh);
        }
    }
}
