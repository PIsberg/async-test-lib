package se.deversity.asynctest.analysis;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StaticPinningScannerTest {

    // --- Fixtures for bytecode scanning ---

    static class SynchronizedBlockFixture {
        public void blockSync() {
            synchronized (this) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static class SynchronizedMethodFixture {
        public synchronized void methodSync() {
            try {
                this.wait(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static class SafeFixture {
        public void safeRun() {
            // Not synchronized, so Thread.sleep is safe and should not be flagged.
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private byte[] getClassBytes(Class<?> clazz) throws IOException {
        String name = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "Could not find class resource for " + clazz.getName());
            return is.readAllBytes();
        }
    }

    @Test
    void testSynchronizedBlockPinningDetection() throws Exception {
        byte[] bytes = getClassBytes(SynchronizedBlockFixture.class);
        List<StaticPinningScanner.PinningSite> sites = StaticPinningScanner.scanClass(bytes);

        assertEquals(1, sites.size(), "Should have detected exactly one pinning site in synchronized block");
        StaticPinningScanner.PinningSite site = sites.get(0);
        assertTrue(site.className().contains("SynchronizedBlockFixture"));
        assertEquals("blockSync", site.methodName());
        assertEquals("java/lang/Thread", site.blockingOwner());
        assertEquals("sleep", site.blockingMethod());
        
        // Test toString format
        String str = site.toString();
        assertTrue(str.contains("SynchronizedBlockFixture#blockSync"));
        assertTrue(str.contains("Thread.sleep() inside a synchronized block"));
    }

    @Test
    void testSynchronizedMethodPinningDetection() throws Exception {
        byte[] bytes = getClassBytes(SynchronizedMethodFixture.class);
        List<StaticPinningScanner.PinningSite> sites = StaticPinningScanner.scanClass(bytes);

        assertEquals(1, sites.size(), "Should have detected exactly one pinning site in synchronized method");
        StaticPinningScanner.PinningSite site = sites.get(0);
        assertTrue(site.className().contains("SynchronizedMethodFixture"));
        assertEquals("methodSync", site.methodName());
        assertEquals("java/lang/Object", site.blockingOwner());
        assertEquals("wait", site.blockingMethod());
    }

    @Test
    void testSafeMethodNoFlagging() throws Exception {
        byte[] bytes = getClassBytes(SafeFixture.class);
        List<StaticPinningScanner.PinningSite> sites = StaticPinningScanner.scanClass(bytes);
        assertTrue(sites.isEmpty(), "Safe method should not have any pinning sites");
    }

    @Test
    void testScanClassViaClassLoader() throws Exception {
        List<StaticPinningScanner.PinningSite> sites = StaticPinningScanner.scanClass(
                SynchronizedBlockFixture.class.getName(),
                StaticPinningScannerTest.class.getClassLoader()
        );
        assertFalse(sites.isEmpty());
    }

    @Test
    void testScanDirectory() throws Exception {
        // Derive the test-classes directory from the running class's code source,
        // so the test works under both Gradle (build/classes/java/test/...) and
        // Maven (target/test-classes/...). Hardcoding either path makes the test
        // unportable across build tools and CI runners.
        Path codeSourceRoot = Path.of(
                StaticPinningScannerTest.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
        Path compiledDir = codeSourceRoot.resolve("se/deversity/asynctest/analysis");
        List<StaticPinningScanner.PinningSite> sites = StaticPinningScanner.scanDirectory(compiledDir);

        // Assert that we found the sites in our synchronized fixtures.
        boolean foundBlock = false;
        boolean foundMethod = false;
        for (StaticPinningScanner.PinningSite site : sites) {
            if (site.className().contains("SynchronizedBlockFixture")) foundBlock = true;
            if (site.className().contains("SynchronizedMethodFixture")) foundMethod = true;
        }
        assertTrue(foundBlock, "Directory scan missed block fixture");
        assertTrue(foundMethod, "Directory scan missed method fixture");
    }

    // --- The scanner's one hard rule: never report a site that cannot exist ---

    /**
     * Calls, inside {@code synchronized}, that the JDK documents as non-blocking.
     *
     * <p>{@code selectNow()} is specified as "a non-blocking selection operation" and
     * {@code getInputStream()}/{@code getOutputStream()} return the stream object without doing
     * I/O. None of them can pin a carrier thread, so none may be reported. The scanner never
     * executes what it inspects, which is why its contract is asymmetric: a missed site is
     * acceptable, an invented one is not, because the user has no way to confirm it from the
     * report and every invented site costs them the time to disprove it.
     */
    static class NonBlockingCallsInsideSynchronizedFixture {
        private final Object lock = new Object();

        public void selectNowIsNotBlocking(java.nio.channels.Selector selector) throws IOException {
            synchronized (lock) {
                selector.selectNow();
            }
        }

        public void gettingAStreamIsNotBlocking(java.net.Socket socket) throws IOException {
            synchronized (lock) {
                socket.getInputStream();
                socket.getOutputStream();
            }
        }
    }

    @Test
    void nonBlockingJdkCallsInsideSynchronizedAreNotReported() throws Exception {
        byte[] bytes = getClassBytes(NonBlockingCallsInsideSynchronizedFixture.class);
        List<StaticPinningScanner.PinningSite> sites = StaticPinningScanner.scanClass(bytes);

        assertTrue(sites.isEmpty(),
                "Every call in this fixture is documented by the JDK as non-blocking, so none of "
                        + "them can pin a carrier thread and none may be reported. A finding here "
                        + "is a false positive, which this scanner is not permitted to produce: "
                        + "it runs without executing the code, so the user cannot confirm the "
                        + "site and can only waste time disproving it. Reported: " + sites);
    }

    /**
     * A file the reader cannot parse must cost one file's findings, not the whole scan.
     *
     * <p>ASM throws unchecked, undeclared exceptions for a class-file version it does not know
     * and for truncated input. {@code scanDirectory} is documented for use from
     * {@code @BeforeAll}, so letting those propagate failed the consumer's build over a stray
     * file — and the failure named ASM, giving no hint that a single artefact was responsible.
     */
    @Test
    void anUnparseableClassFileIsSkippedRatherThanFailingTheScan(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        java.nio.file.Files.write(dir.resolve("Corrupt.class"),
                new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x01});
        java.nio.file.Files.write(dir.resolve("Good.class"),
                getClassBytes(SynchronizedBlockFixture.class));

        List<StaticPinningScanner.PinningSite> sites = StaticPinningScanner.scanDirectory(dir);

        assertEquals(1, sites.size(),
                "The corrupt file must be skipped and the valid one still scanned. Either an "
                        + "exception escaped scanDirectory — which fails the consumer's build "
                        + "over a file that is not their code — or the skip swallowed the good "
                        + "file's finding too. Reported: " + sites);
        assertEquals("blockSync", sites.get(0).methodName());
    }
}
