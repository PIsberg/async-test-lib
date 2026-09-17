package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates method body extraction and helper resolution in {@link LaneSource}.
 */
class LaneSourceTest {

    @Test
    @DisplayName("bodyOf extracts simple method body")
    void extractsSimpleMethodBody() {
        String source = """
                class TestSuite {
                    void testOne() {
                        int x = 42;
                        System.out.println(x);
                    }
                }
                """;
        String body = LaneSource.bodyOf(source, "testOne");
        assertTrue(body.contains("int x = 42;"));
        assertTrue(body.startsWith("{"));
        assertTrue(body.endsWith("}"));
    }

    @Test
    @DisplayName("bodyOf handles method with annotations and throws clause")
    void extractsMethodWithThrowsClause() {
        String source = """
                class TestSuite {
                    @AsyncTest
                    public void testWithThrows() throws IOException, InterruptedException {
                        doSomething();
                    }
                }
                """;
        String body = LaneSource.bodyOf(source, "testWithThrows");
        assertTrue(body.contains("doSomething();"));
    }

    @Test
    @DisplayName("bodyOf rejects statement keywords to prevent matching keyword blocks as methods")
    void rejectsStatementKeywords() {
        String source = """
                class TestSuite {
                    void testMethod() {
                        synchronized (lock) {
                            callSomething();
                        }
                    }
                }
                """;
        assertEquals("", LaneSource.bodyOf(source, "synchronized"));
        assertEquals("", LaneSource.bodyOf(source, "if"));
        assertEquals("", LaneSource.bodyOf(source, "for"));
        assertEquals("", LaneSource.bodyOf(source, "while"));
    }

    @Test
    @DisplayName("bodyOf returns empty string for non-existent method")
    void returnsEmptyForMissingMethod() {
        String source = "class TestSuite { void methodA() {} }";
        assertEquals("", LaneSource.bodyOf(source, "nonExistentMethod"));
    }

    @Test
    @DisplayName("bodyWithHelpers appends bodies of called helper methods")
    void appendsHelperBodies() {
        String source = """
                class TestSuite {
                    void testMethod() {
                        helperA();
                    }
                    private void helperA() {
                        System.out.println("inside helperA");
                    }
                }
                """;
        String reachable = LaneSource.bodyWithHelpers(source, "testMethod");
        assertTrue(reachable.contains("helperA();"));
        assertTrue(reachable.contains("inside helperA"));
    }
}
