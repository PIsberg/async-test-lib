package se.deversity.asynctest.report;

import java.io.File;

final class ReportListeners {

    private ReportListeners() {}

    static String resolveDefaultOutputDir() {
        if (new File("target").isDirectory()) return "target/async-test-reports";
        if (new File("build").isDirectory())  return "build/async-test-reports";
        return "async-test-reports";
    }

    /**
     * The library version from the jar manifest, or {@code "unknown"} when the classes are not
     * packaged (the library's own test run). The literal this replaced still said 1.6.0 when the build was at 1.12.1 (#703).
     */
    static String libraryVersion() {
        Package p = ReportListeners.class.getPackage();
        String v = (p == null) ? null : p.getImplementationVersion();
        return v == null ? "unknown" : v;
    }
}
