package se.deversity.asynctest.intellij.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReportLocatorTest {

    @TempDir
    Path project;

    @Test
    void locate_findsTheReportUnderTheProjectBasePath() throws IOException {
        Path report = touch("target/async-test-report.json");

        assertEquals(report, ReportLocator.locate(project.toString(), "target/async-test-report.json"));
    }

    @Test
    void locate_triesThePatternsInOrderAndTakesTheFirstThatExists() throws IOException {
        touch("build/async-test-report.json");
        Path maven = touch("target/async-test-report.json");

        assertEquals(maven, ReportLocator.locate(project.toString(),
                "missing/report.json, target/async-test-report.json, build/async-test-report.json"));
    }

    @Test
    void locate_skipsBlankPatternsAndTrimsTheRest() throws IOException {
        Path report = touch("build/async-test-report.json");

        assertEquals(report, ReportLocator.locate(project.toString(), " , ,  build/async-test-report.json  ,"));
    }

    @Test
    void locate_returnsNullWhenNothingMatches() {
        assertNull(ReportLocator.locate(project.toString(), "target/async-test-report.json"));
    }

    @Test
    void locate_returnsNullForAProjectWithNoBasePath() {
        assertNull(ReportLocator.locate(null, "target/async-test-report.json"));
    }

    @Test
    void locate_ignoresADirectoryThatHappensToMatch() throws IOException {
        Files.createDirectories(project.resolve("target/async-test-report.json"));

        assertNull(ReportLocator.locate(project.toString(), "target/async-test-report.json"),
                "a directory is not a report; the panel would show it as an empty parse");
    }

    private Path touch(String relative) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");
        return file;
    }
}
