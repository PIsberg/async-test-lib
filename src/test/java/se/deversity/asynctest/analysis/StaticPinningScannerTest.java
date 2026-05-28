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
}
