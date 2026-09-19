package se.deversity.asynctest.report;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What every file-writing report listener does apart from its format: collect findings from any
 * thread, write them at most once, and never let a failed write fail the build being reported on.
 * The listeners are {@code public final} API, so they share this by holding one rather than by
 * extending anything (#701).
 */
final class ReportSink {

    /** Renders {@code findings} into {@code file}; the only part a format owns. */
    @FunctionalInterface
    interface Writer {
        void write(Path file, List<DetectorFinding> findings) throws IOException;
    }

    private final List<DetectorFinding> findings = new CopyOnWriteArrayList<>();
    private final AtomicBoolean flushed = new AtomicBoolean(false);
    private final String outputDir;
    private final String fileName;
    private final String formatName;
    private final Writer writer;

    /**
     * @param formatName the noun in the stderr line when the write fails, for example
     *                   {@code "JSON"} in {@code "async-test: Failed to write JSON report: ..."}
     */
    ReportSink(String outputDir, String fileName, String formatName, Writer writer) {
        this.outputDir = outputDir;
        this.fileName = fileName;
        this.formatName = formatName;
        this.writer = writer;
    }

    /** Flushes at JVM exit. Called after construction, so the hook never sees a half-built sink. */
    void flushOnShutdown(String threadName) {
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, threadName));
    }

    void add(String detectorName, IssueSeverity severity, String report) {
        findings.add(new DetectorFinding(detectorName, severity, report, System.currentTimeMillis()));
    }

    /** Writes once: the first caller with findings wins the CAS, every later call is a no-op. */
    @Nullable Path flush() {
        if (findings.isEmpty() || !flushed.compareAndSet(false, true)) {
            return null;
        }
        try {
            Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            writer.write(file, List.copyOf(findings));
            return file;
        } catch (IOException e) {
            System.err.println("async-test: Failed to write " + formatName + " report: "
                    + e.getMessage());
            return null;
        }
    }

    int count() {
        return findings.size();
    }
}
