package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ThreadFactoryDetector.
 */
public class ThreadFactoryDetectorTest {

    @Test
    void testGoodThreadFactoryUsage() {
        ThreadFactoryDetector detector = new ThreadFactoryDetector();
        ThreadFactory factory = new ThreadFactory() {
            private int count = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "good-thread-" + (++count));
                t.setUncaughtExceptionHandler((thread, ex) -> {});
                t.setDaemon(true);
                return t;
            }
        };

        detector.registerFactory(factory, "goodFactory");
        Thread thread = factory.newThread(() -> {});
        detector.recordThreadCreated(factory, "goodFactory", thread);

        ThreadFactoryDetector.ThreadFactoryReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Good factory should not report issues");
    }

    @Test
    void testMissingExceptionHandlerDetection() {
        ThreadFactoryDetector detector = new ThreadFactoryDetector();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "no-handler");
                // Missing setUncaughtExceptionHandler!
                return t;
            }
        };

        detector.registerFactory(factory, "badFactory");
        Thread thread = factory.newThread(() -> {});
        detector.recordThreadCreated(factory, "badFactory", thread);

        ThreadFactoryDetector.ThreadFactoryReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect missing exception handler");
    }

    @Test
    void testNonDaemonThreadDetection() {
        ThreadFactoryDetector detector = new ThreadFactoryDetector();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "non-daemon");
                t.setUncaughtExceptionHandler((thread, ex) -> {});
                t.setDaemon(false);  // Non-daemon!
                return t;
            }
        };

        detector.registerFactory(factory, "nonDaemonFactory");
        Thread thread = factory.newThread(() -> {});
        detector.recordThreadCreated(factory, "nonDaemonFactory", thread);

        ThreadFactoryDetector.ThreadFactoryReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect non-daemon thread");
    }

    @Test
    void testUnnamedThreadDetection() {
        ThreadFactoryDetector detector = new ThreadFactoryDetector();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);  // No name!
                t.setUncaughtExceptionHandler((thread, ex) -> {});
                return t;
            }
        };

        detector.registerFactory(factory, "unnamedFactory");
        Thread thread = factory.newThread(() -> {});
        detector.recordThreadCreated(factory, "unnamedFactory", thread);

        ThreadFactoryDetector.ThreadFactoryReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect unnamed thread");
    }

    @Test
    void testDefaultNamedThreadDetection() {
        ThreadFactoryDetector detector = new ThreadFactoryDetector();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                // Default Thread- name pattern
                return new Thread(r);
            }
        };

        detector.registerFactory(factory, "defaultNameFactory");
        Thread thread = factory.newThread(() -> {});
        detector.recordThreadCreated(factory, "defaultNameFactory", thread);

        ThreadFactoryDetector.ThreadFactoryReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect default Thread- naming");
    }

    @Test
    void testMultiThreadCreation() throws Exception {
        ThreadFactoryDetector detector = new ThreadFactoryDetector();
        ThreadFactory factory = new ThreadFactory() {
            private int count = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pool-thread-" + (++count));
                t.setUncaughtExceptionHandler((thread, ex) -> {});
                t.setDaemon(true);
                return t;
            }
        };

        detector.registerFactory(factory, "poolFactory");

        for (int i = 0; i < 5; i++) {
            Thread thread = factory.newThread(() -> {});
            detector.recordThreadCreated(factory, "poolFactory", thread);
        }

        ThreadFactoryDetector.ThreadFactoryReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-thread creation should work correctly");
    }

    @Test
    void testReportToString() {
        ThreadFactoryDetector detector = new ThreadFactoryDetector();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r);  // No handler, no name
            }
        };

        detector.registerFactory(factory, "testFactory");
        Thread thread = factory.newThread(() -> {});
        detector.recordThreadCreated(factory, "testFactory", thread);

        ThreadFactoryDetector.ThreadFactoryReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("THREADFACTORY ISSUES DETECTED"), "Report should have header");
    }
}
