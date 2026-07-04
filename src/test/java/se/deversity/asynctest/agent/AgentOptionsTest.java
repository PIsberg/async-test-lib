package se.deversity.asynctest.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOptionsTest {

    @Test
    void parse_nullArgs_yieldsEmptyLists() {
        AgentOptions options = AgentOptions.parse(null);
        assertTrue(options.includes().isEmpty(), "null args must produce no includes");
        assertTrue(options.excludes().isEmpty(), "null args must produce no excludes");
    }

    @Test
    void parse_emptyArgs_yieldsEmptyLists() {
        AgentOptions options = AgentOptions.parse("");
        assertTrue(options.includes().isEmpty());
        assertTrue(options.excludes().isEmpty());
    }

    @Test
    void parse_blankArgs_yieldsEmptyLists() {
        AgentOptions options = AgentOptions.parse("   ");
        assertTrue(options.includes().isEmpty());
        assertTrue(options.excludes().isEmpty());
    }

    @Test
    void parse_singleInclude() {
        AgentOptions options = AgentOptions.parse("includes=com.myapp");
        assertEquals(List.of("com.myapp"), options.includes());
        assertTrue(options.excludes().isEmpty());
    }

    @Test
    void parse_multipleIncludes_semicolonSeparatedValueList() {
        AgentOptions options = AgentOptions.parse("includes=com.myapp;com.other");
        assertEquals(List.of("com.myapp", "com.other"), options.includes());
    }

    @Test
    void parse_includesAndExcludes() {
        AgentOptions options = AgentOptions.parse("includes=com.myapp;excludes=com.myapp.dto");
        assertEquals(List.of("com.myapp"), options.includes());
        assertEquals(List.of("com.myapp.dto"), options.excludes());
    }

    @Test
    void parse_includesAndExcludes_commaSeparatedKeys() {
        AgentOptions options = AgentOptions.parse("includes=com.myapp,excludes=com.myapp.dto");
        assertEquals(List.of("com.myapp"), options.includes());
        assertEquals(List.of("com.myapp.dto"), options.excludes());
    }

    @Test
    void parse_trimsWhitespaceAroundKeysAndValues() {
        AgentOptions options = AgentOptions.parse("  includes = com.myapp ;  com.other  ");
        assertEquals(List.of("com.myapp", "com.other"), options.includes());
    }

    @Test
    void parse_keysAreCaseInsensitive() {
        AgentOptions options = AgentOptions.parse("INCLUDES=com.myapp;Excludes=com.myapp.dto");
        assertEquals(List.of("com.myapp"), options.includes());
        assertEquals(List.of("com.myapp.dto"), options.excludes());
    }

    @Test
    void parse_unknownKeysAreIgnored() {
        AgentOptions options = AgentOptions.parse("bogus=whatever,includes=com.myapp");
        assertEquals(List.of("com.myapp"), options.includes());
        assertTrue(options.excludes().isEmpty());
    }

    @Test
    void parse_emptyEntriesAreSkipped() {
        AgentOptions options = AgentOptions.parse(",;includes=com.myapp;;,,excludes=com.dto;");
        assertEquals(List.of("com.myapp"), options.includes());
        assertEquals(List.of("com.dto"), options.excludes());
    }

    @Test
    void parse_keyWithEmptyValueContributesNothing() {
        AgentOptions options = AgentOptions.parse("includes=");
        assertTrue(options.includes().isEmpty(),
                "a key with no value must not add an empty prefix");
    }

    @Test
    void parse_bareTokenBeforeAnyKeyIsIgnored() {
        AgentOptions options = AgentOptions.parse("com.stray;includes=com.myapp");
        assertEquals(List.of("com.myapp"), options.includes());
    }

    @Test
    void includesAndExcludes_returnImmutableLists() {
        AgentOptions options = AgentOptions.parse("includes=com.myapp;excludes=com.dto");
        assertThrowsUnsupported(() -> options.includes().add("x"));
        assertThrowsUnsupported(() -> options.excludes().add("x"));
    }

    private static void assertThrowsUnsupported(Runnable r) {
        try {
            r.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("expected returned list to be immutable");
    }
}
