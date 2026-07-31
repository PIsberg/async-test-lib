package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

import static org.junit.jupiter.api.Assertions.*;

class WeakHashMapSharedDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new WeakHashMapSharedDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void sharedWeakHashMapIsFlagged() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new WeakHashMap<String, String>();
        d.recordAccess(m, "weak-cache", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(m, "weak-cache", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("weak-cache"));
        assertTrue(msg.contains("WeakHashMap"));
        assertTrue(msg.contains("infinite loops"),
                "WeakHashMap-specific risk text must appear: " + msg);
    }

    @Test
    void sharedIdentityHashMapIsFlagged() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new IdentityHashMap<Object, Object>();
        d.recordAccess(m, "id-map", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(m, "id-map", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("id-map"));
        assertTrue(msg.contains("IdentityHashMap"));
        assertTrue(msg.contains("linear probing"),
                "IdentityHashMap-specific risk text must appear: " + msg);
    }

    @Test
    void otherMapTypesAreIgnored() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var m = new HashMap<String, String>();
        d.recordAccess(m, "regular-hashmap", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(m, "regular-hashmap", Thread.currentThread()));
        t.start();
        t.join();
        assertFalse(d.analyze().hasIssues(),
                "Regular HashMap is not WeakHashMap or IdentityHashMap — not in scope of THIS detector");
    }

    @Test
    void singleThreadAccessIsNotFlagged() {
        var d = new WeakHashMapSharedDetector();
        var m = new WeakHashMap<String, String>();
        for (int i = 0; i < 5; i++) {
            d.recordAccess(m, "solo", Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullsAreIgnored() {
        var d = new WeakHashMapSharedDetector();
        d.recordAccess(null, "x", Thread.currentThread());
        d.recordAccess(new WeakHashMap<>(), "x", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void structuredViolationCarriesTypeAttribute() throws Exception {
        var d = new WeakHashMapSharedDetector();
        var w = new WeakHashMap<String, String>();
        var i = new IdentityHashMap<Object, Object>();
        d.recordAccess(w, "w-1", Thread.currentThread());
        d.recordAccess(i, "i-1", Thread.currentThread());
        Thread t = new Thread(() -> {
            d.recordAccess(w, "w-1", Thread.currentThread());
            d.recordAccess(i, "i-1", Thread.currentThread());
        });
        t.start();
        t.join();
        var sv = d.analyze().structuredViolations;
        assertEquals(2, sv.size());
        var types = sv.stream().map(v -> v.attributes().get("type")).toList();
        assertTrue(types.contains("WeakHashMap"));
        assertTrue(types.contains("IdentityHashMap"));
    }
}
