package se.deversity.asynctest.report;

import java.io.File;

final class ReportListeners {

    private ReportListeners() {}

    static String resolveDefaultOutputDir() {
        if (new File("target").isDirectory()) return "target/async-test-reports";
        if (new File("build").isDirectory())  return "build/async-test-reports";
        return "async-test-reports";
    }
}
