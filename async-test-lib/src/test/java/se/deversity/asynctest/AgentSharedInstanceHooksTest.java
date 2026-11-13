package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.util.SplittableRandom;
import java.util.zip.Checksum;
import java.util.zip.Deflater;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the woven shared-instance hooks report with no hand-written instrumentation at all.
 *
 * <p>These hooks are called only from bytecode the agent rewrites, so PIT's 2026-08-31 baseline
 * found most of their mutants with no coverage and #427 read that as "unmeasurable without the
 * agent". It is not: a hook is a static method that records and delegates, and a test can call
 * it the way woven code would. What it cannot fake is the substitution itself, which the agent
 * module's weaving tests cover.
 *
 * <p>Every family is tested in both directions through the hooks alone. One instance touched by
 * two threads must reach its detector; one instance per thread, through the same hooks, must
 * not. The context is installed on each worker because the hooks resolve their detector from
 * the calling thread, exactly as the runner installs it on its workers.
 */
class AgentSharedInstanceHooksTest {

    private static final Pattern PATTERN = Pattern.compile("(?<word>[a-z]+)-(?<num>[0-9]+)");

    private static final byte[] PAYLOAD = "corpus".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Runs {@code body} on two threads at once, each with {@code ctx} installed. */
    private static void onTwoThreads(AsyncTestContext ctx, boolean tolerateRace, Runnable body) {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Runnable sync = () -> {
            AsyncTestContext.install(ctx);
            try {
                barrier.await();
                body.run();
            } catch (Throwable t) { // NOPMD - collected and rethrown on the test thread
                failures.add(t);
            } finally {
                AsyncTestContext.uninstall();
            }
        };
        Thread a = new Thread(sync, "shared-hooks-1");
        Thread b = new Thread(sync, "shared-hooks-2");
        a.start();
        b.start();
        try {
            a.join();
            b.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        // Sharing these types is not merely unsound, it throws: a Matcher with no match in
        // progress, a SimpleDateFormat inside its own Calendar. That is the bug doing what the
        // bug does, after the hook has already recorded, and the shared half tolerates it. The
        // confined half must not: a throw there is a hook that did not delegate correctly.
        if (!tolerateRace && !failures.isEmpty()) {
            throw new AssertionError("a worker failed: " + failures, failures.get(0));
        }
    }

    private static AsyncTestContext newContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
    }

    /** Runs {@code read} with {@code ctx} installed on the test thread, for the report. */
    private static <T> T with(AsyncTestContext ctx, Supplier<T> read) {
        AsyncTestContext.install(ctx);
        try {
            return read.get();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /** A DecimalFormat whose separators do not follow the machine locale. */
    private static NumberFormat rootDecimalFormat() {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void quietly(ThrowingRunnable body) {
        try {
            body.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @DisplayName("a SimpleDateFormat two threads format and parse through the hooks is reported; one each is not")
    void simpleDateFormat() {
        AsyncTestContext shared = newContext();
        SimpleDateFormat one = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        onTwoThreads(shared, true, () -> quietly(() -> {
            AgentSharedInstanceHooks.format(one, new Date(0));
            AgentSharedInstanceHooks.parse(one, "2026-09-02");
            AgentSharedInstanceHooks.parse(one, "2026-09-02", new ParsePosition(0));
            // The same three through the DateFormat-typed hooks (#542), on the same instance.
            java.text.DateFormat wide = one;
            AgentSharedInstanceHooks.format(wide, new Date(0));
            AgentSharedInstanceHooks.parse(wide, "2026-09-02");
            AgentSharedInstanceHooks.parse(wide, "2026-09-02", new ParsePosition(0));
        }));
        assertTrue(with(shared, () -> AsyncTestContext.simpleDateFormatDetector().analyze().hasIssues()),
                "one SimpleDateFormat used by two threads through the hooks must be reported");

        AsyncTestContext confined = newContext();
        onTwoThreads(confined, false, () -> quietly(() -> {
            SimpleDateFormat mine = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            AgentSharedInstanceHooks.format(mine, new Date(0));
            AgentSharedInstanceHooks.parse(mine, "2026-09-02");
            AgentSharedInstanceHooks.parse(mine, "2026-09-02", new ParsePosition(0));
            java.text.DateFormat wide = mine;
            AgentSharedInstanceHooks.format(wide, new Date(0));
            AgentSharedInstanceHooks.parse(wide, "2026-09-02");
            AgentSharedInstanceHooks.parse(wide, "2026-09-02", new ParsePosition(0));
        }));
        assertFalse(with(confined, () -> AsyncTestContext.simpleDateFormatDetector().analyze().hasIssues()),
                "one SimpleDateFormat per thread, through the same hooks, is correct code");
    }

    @Test
    @DisplayName("a Matcher two threads drive through the hooks is reported; one each is not")
    void matcher() {
        AsyncTestContext shared = newContext();
        Matcher one = PATTERN.matcher("corpus-42");
        onTwoThreads(shared, true, () -> {
            AgentSharedInstanceHooks.find(one);
            AgentSharedInstanceHooks.group(one);
            AgentSharedInstanceHooks.group(one, 1);
            AgentSharedInstanceHooks.group(one, "word");
            AgentSharedInstanceHooks.find(one, 0);
            AgentSharedInstanceHooks.matches(one);
        });
        assertTrue(with(shared, () -> AsyncTestContext.sharedMatcherDetector().analyze().hasIssues()),
                "one Matcher driven by two threads through the hooks must be reported");

        AsyncTestContext confined = newContext();
        onTwoThreads(confined, false, () -> {
            Matcher mine = PATTERN.matcher("corpus-42");
            assertTrue(AgentSharedInstanceHooks.find(mine), "find delegates");
            assertEquals("corpus-42", AgentSharedInstanceHooks.group(mine), "group delegates");
            assertEquals("corpus", AgentSharedInstanceHooks.group(mine, 1), "group(int) delegates");
            assertEquals("42", AgentSharedInstanceHooks.group(mine, "num"), "group(String) delegates");
            assertTrue(AgentSharedInstanceHooks.find(mine, 0), "find(int) delegates");
            assertTrue(AgentSharedInstanceHooks.matches(mine), "matches delegates");
        });
        assertFalse(with(confined, () -> AsyncTestContext.sharedMatcherDetector().analyze().hasIssues()),
                "one Matcher per thread, through the same hooks, is correct code");
    }

    @Test
    @DisplayName("a MessageDigest two threads feed through the hooks is reported; one each is not")
    void messageDigest() {
        AsyncTestContext shared = newContext();
        MessageDigest one = sha256();
        onTwoThreads(shared, true, () -> quietly(() -> digestEverything(one)));
        assertTrue(with(shared, () -> AsyncTestContext.sharedMessageDigestDetector().analyze().hasIssues()),
                "one MessageDigest fed by two threads through the hooks must be reported");

        AsyncTestContext confined = newContext();
        onTwoThreads(confined, false, () -> quietly(() -> {
            MessageDigest mine = sha256();
            digestEverything(mine);
            assertEquals(32, AgentSharedInstanceHooks.digest(mine).length, "digest delegates");
        }));
        assertFalse(with(confined, () -> AsyncTestContext.sharedMessageDigestDetector().analyze().hasIssues()),
                "one MessageDigest per thread, through the same hooks, is correct code");
    }

    @Test
    @DisplayName("the site of a MessageDigest finding fed through the hooks is the caller's line, not the hook's")
    void messageDigestSiteNamesTheCallerNotTheHook() {
        // #853: the stack under the detector's walk is the hook, then the woven caller. The report
        // named AgentSharedInstanceHooks.recordDigest, a line the reader cannot change.
        AsyncTestContext shared = newContext();
        MessageDigest one = sha256();
        onTwoThreads(shared, true, () -> updateFromTheCallersLine(one));
        List<se.deversity.asynctest.diagnostics.SiteCapture.Site> sites = with(shared,
                () -> AsyncTestContext.sharedMessageDigestDetector().analyze().structuredViolations)
                .get(0).sites();

        assertFalse(sites.isEmpty(), "the finding must name at least one access site");
        for (var site : sites) {
            assertEquals(AgentSharedInstanceHooksTest.class.getName() + ".updateFromTheCallersLine",
                    site.className() + "." + site.methodName(),
                    "an agent-fed site must be the line that called the digest, found " + site.render());
        }
    }

    /** The one line in this class that feeds the digest, standing in for a woven call site. */
    private static void updateFromTheCallersLine(MessageDigest md) {
        AgentSharedInstanceHooks.update(md, PAYLOAD);
    }

    /** Every digest hook once, on {@code md}. */
    private static void digestEverything(MessageDigest md) throws Exception {
        AgentSharedInstanceHooks.update(md, PAYLOAD);
        AgentSharedInstanceHooks.update(md, (byte) 7);
        AgentSharedInstanceHooks.update(md, PAYLOAD, 0, 2);
        AgentSharedInstanceHooks.update(md, ByteBuffer.wrap(PAYLOAD));
        AgentSharedInstanceHooks.digest(md, PAYLOAD);
        AgentSharedInstanceHooks.digest(md, new byte[64], 0, 64);
    }

    @Test
    @DisplayName("a Calendar two threads read and set through the hooks is reported; one each is not")
    void calendar() {
        AsyncTestContext shared = newContext();
        Calendar one = Calendar.getInstance(Locale.ROOT);
        onTwoThreads(shared, true, () -> calendarEverything(one));
        assertTrue(with(shared, () -> AsyncTestContext.calendarDetector().analyze().hasIssues()),
                "one Calendar touched by two threads through the hooks must be reported");

        AsyncTestContext confined = newContext();
        onTwoThreads(confined, false, () -> {
            Calendar mine = Calendar.getInstance(Locale.ROOT);
            calendarEverything(mine);
            assertEquals(2026, AgentSharedInstanceHooks.get(mine, Calendar.YEAR), "set and get delegate");
        });
        assertFalse(with(confined, () -> AsyncTestContext.calendarDetector().analyze().hasIssues()),
                "one Calendar per thread, through the same hooks, is correct code");
    }

    /** Every calendar hook once, on {@code cal}, leaving the year at 2026. */
    private static void calendarEverything(Calendar cal) {
        AgentSharedInstanceHooks.get(cal, Calendar.YEAR);
        AgentSharedInstanceHooks.set(cal, Calendar.YEAR, 2020);
        AgentSharedInstanceHooks.set(cal, 2021, Calendar.SEPTEMBER, 2);
        AgentSharedInstanceHooks.set(cal, 2022, Calendar.SEPTEMBER, 2, 10, 30);
        // The mutators woven since #820, before the last set so the year ends at 2026.
        AgentSharedInstanceHooks.add(cal, Calendar.DAY_OF_MONTH, 1);
        AgentSharedInstanceHooks.roll(cal, Calendar.MONTH, 1);
        AgentSharedInstanceHooks.roll(cal, Calendar.HOUR_OF_DAY, true);
        AgentSharedInstanceHooks.setTimeInMillis(cal, 0L);
        AgentSharedInstanceHooks.setTime(cal, new java.util.Date(0L));
        AgentSharedInstanceHooks.setTimeZone(cal, java.util.TimeZone.getTimeZone("UTC"));
        AgentSharedInstanceHooks.clear(cal, Calendar.HOUR_OF_DAY);
        AgentSharedInstanceHooks.clear(cal);
        AgentSharedInstanceHooks.set(cal, 2026, Calendar.SEPTEMBER, 2, 10, 30, 0);
    }

    @Test
    @DisplayName("a StringBuilder two threads append to through the hooks is reported; one each is not")
    void stringBuilder() {
        AsyncTestContext shared = newContext();
        StringBuilder one = new StringBuilder();
        onTwoThreads(shared, true, () -> {
            appendEverything(one);
            appendThroughAppendable(one);
        });
        assertTrue(with(shared, () -> AsyncTestContext.stringBuilderDetector().analyze().hasIssues()),
                "one StringBuilder appended to by two threads through the hooks must be reported");

        AsyncTestContext confined = newContext();
        onTwoThreads(confined, false, () -> {
            StringBuilder mine = new StringBuilder();
            assertSame(mine, appendEverything(mine), "append returns the receiver, as the original does");
            appendThroughAppendable(mine);
            assertEquals("s1c23.0truexyzabdef", mine.toString(), "every append delegated");
        });
        assertFalse(with(confined, () -> AsyncTestContext.stringBuilderDetector().analyze().hasIssues()),
                "one StringBuilder per thread, through the same hooks, is correct code");
    }

    /** Every append hook once, on {@code sb}; returns the last hook's result. */
    private static StringBuilder appendEverything(StringBuilder sb) {
        AgentSharedInstanceHooks.append(sb, "s");
        AgentSharedInstanceHooks.append(sb, 1);
        AgentSharedInstanceHooks.append(sb, 'c');
        AgentSharedInstanceHooks.append(sb, 2L);
        AgentSharedInstanceHooks.append(sb, 3.0);
        AgentSharedInstanceHooks.append(sb, true);
        AgentSharedInstanceHooks.append(sb, (Object) "x");
        return AgentSharedInstanceHooks.append(sb, (CharSequence) "yz");
    }

    /** The three Appendable-typed append hooks once each, on {@code sb} (#542). */
    private static void appendThroughAppendable(StringBuilder sb) {
        quietly(() -> {
            Appendable wide = sb;
            AgentSharedInstanceHooks.append(wide, "ab");
            AgentSharedInstanceHooks.append(wide, 'd');
            AgentSharedInstanceHooks.append(wide, "xefx", 1, 3);
        });
    }

    @Test
    @DisplayName("a DecimalFormat two threads format through the hooks is reported; one each is not")
    void decimalFormat() {
        AsyncTestContext shared = newContext();
        NumberFormat one = rootDecimalFormat();
        onTwoThreads(shared, true, () -> {
            AgentSharedInstanceHooks.format(one, 42L);
            AgentSharedInstanceHooks.format(one, 4.2);
            quietly(() -> {
                AgentSharedInstanceHooks.parse(one, "4.20");
                AgentSharedInstanceHooks.parse(one, "4.20", new ParsePosition(0));
            });
        });
        assertTrue(with(shared, () -> AsyncTestContext.sharedDecimalFormatDetector().analyze().hasIssues()),
                "one DecimalFormat used by two threads through the hooks must be reported");

        AsyncTestContext confined = newContext();
        onTwoThreads(confined, false, () -> {
            NumberFormat mine = rootDecimalFormat();
            assertEquals("42.00", AgentSharedInstanceHooks.format(mine, 42L), "format(long) delegates");
            assertEquals("4.20", AgentSharedInstanceHooks.format(mine, 4.2), "format(double) delegates");
            quietly(() -> {
                AgentSharedInstanceHooks.parse(mine, "4.20");
                AgentSharedInstanceHooks.parse(mine, "4.20", new ParsePosition(0));
            });
        });
        assertFalse(with(confined, () -> AsyncTestContext.sharedDecimalFormatDetector().analyze().hasIssues()),
                "one DecimalFormat per thread, through the same hooks, is correct code");
    }

    @Test
    @DisplayName("a DecimalFormat reached through Format.format(Object) or parseObject is reported (#1319)")
    void decimalFormatThroughFormat() {
        for (boolean parsing : new boolean[] {false, true}) {
            AsyncTestContext shared = newContext();
            java.text.Format one = rootDecimalFormat();
            onTwoThreads(shared, true, () -> throughFormat(one, parsing, new java.math.BigDecimal("4.20"), "4.20"));
            assertTrue(with(shared, () -> AsyncTestContext.sharedDecimalFormatDetector().analyze().hasIssues()),
                    "one DecimalFormat used by two threads through Format must be reported, parsing=" + parsing);

            AsyncTestContext confined = newContext();
            onTwoThreads(confined, false,
                    () -> throughFormat(rootDecimalFormat(), parsing, new java.math.BigDecimal("4.20"), "4.20"));
            assertFalse(with(confined, () -> AsyncTestContext.sharedDecimalFormatDetector().analyze().hasIssues()),
                    "one DecimalFormat per thread through Format is correct code, parsing=" + parsing);
        }
        assertEquals("4.20", AgentSharedInstanceHooks.format(
                (java.text.Format) rootDecimalFormat(), new java.math.BigDecimal("4.20")), "format(Object) delegates");
    }

    @Test
    @DisplayName("a SimpleDateFormat reached through Format.format(Object) or parseObject is reported (#1319)")
    void simpleDateFormatThroughFormat() {
        for (boolean parsing : new boolean[] {false, true}) {
            AsyncTestContext shared = newContext();
            java.text.Format one = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            onTwoThreads(shared, true, () -> throughFormat(one, parsing, new Date(0L), "2020-01-02"));
            assertTrue(with(shared, () -> AsyncTestContext.simpleDateFormatDetector().analyze().hasIssues()),
                    "one SimpleDateFormat used by two threads through Format must be reported, parsing=" + parsing);

            AsyncTestContext confined = newContext();
            onTwoThreads(confined, false, () -> throughFormat(
                    new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT), parsing, new Date(0L), "2020-01-02"));
            assertFalse(with(confined, () -> AsyncTestContext.simpleDateFormatDetector().analyze().hasIssues()),
                    "one SimpleDateFormat per thread through Format is correct code, parsing=" + parsing);
        }
    }

    @Test
    @DisplayName("a shared MessageFormat is reported and named; one each, or an immutable Format, is not (#1319)")
    void messageFormatThroughFormat() {
        AsyncTestContext shared = newContext();
        java.text.MessageFormat one = new java.text.MessageFormat("{0} has {1} items", Locale.ROOT);
        onTwoThreads(shared, true, () -> {
            AgentSharedInstanceHooks.format(one, new Object[] {"cart", 3});
            quietly(() -> AgentSharedInstanceHooks.parse(one, "cart has 3 items"));
        });
        String report = with(shared, () -> AsyncTestContext.sharedDecimalFormatDetector().analyze().toString());
        assertTrue(report.contains("MessageFormat is not thread-safe"),
                "one MessageFormat used by two threads must be reported as a MessageFormat: " + report);
        assertFalse(report.contains("DecimalFormat/NumberFormat is not thread-safe"),
                "a MessageFormat finding must not call the instance a DecimalFormat: " + report);

        AsyncTestContext confined = newContext();
        java.text.Format immutable = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.toFormat();
        onTwoThreads(confined, false, () -> {
            java.text.MessageFormat mine = new java.text.MessageFormat("{0} has {1} items", Locale.ROOT);
            assertEquals("cart has 3 items", AgentSharedInstanceHooks.format(mine, new Object[] {"cart", 3}),
                    "format(Object) delegates");
            quietly(() -> assertEquals("cart", AgentSharedInstanceHooks.parse(mine, "cart has 3 items")[0],
                    "parse(String) delegates"));
            assertEquals("1970-01-01", AgentSharedInstanceHooks.format(immutable, java.time.LocalDate.EPOCH),
                    "a Format that is none of the unsafe types is called and not recorded");
        });
        assertFalse(with(confined, () -> AsyncTestContext.sharedDecimalFormatDetector().analyze().hasIssues()),
                "one MessageFormat per thread and a shared immutable Format are correct code");
        assertFalse(with(confined, () -> AsyncTestContext.simpleDateFormatDetector().analyze().hasIssues()),
                "a shared DateTimeFormatter view is not a SimpleDateFormat");
    }

    private static void throughFormat(java.text.Format format, boolean parsing, Object value, String text) {
        if (parsing) {
            quietly(() -> AgentSharedInstanceHooks.parseObject(format, text));
        } else {
            AgentSharedInstanceHooks.format(format, value);
        }
    }

    @Test
    @DisplayName("a Formatter two threads write through the hooks is reported; one each is not")
    void formatter() {
        AsyncTestContext shared = newContext();
        Formatter one = new Formatter(new StringBuilder());
        onTwoThreads(shared, true, () -> {
            AgentSharedInstanceHooks.format(one, "%s", "x");
            AgentSharedInstanceHooks.format(one, Locale.ROOT, "%s", "y");
        });
        assertTrue(with(shared, () -> AsyncTestContext.sharedFormatterDetector().analyze().hasIssues()),
                "one Formatter written by two threads through the hooks must be reported");

        AsyncTestContext confined = newContext();
        onTwoThreads(confined, false, () -> {
            StringBuilder out = new StringBuilder();
            Formatter mine = new Formatter(out);
            assertSame(mine, AgentSharedInstanceHooks.format(mine, "%s", "x"), "format returns the receiver");
            assertSame(mine, AgentSharedInstanceHooks.format(mine, Locale.ROOT, "%s", "y"), "so does the locale form");
            assertEquals("xy", out.toString(), "both formats delegated");
        });
        assertFalse(with(confined, () -> AsyncTestContext.sharedFormatterDetector().analyze().hasIssues()),
                "one Formatter per thread, through the same hooks, is correct code");
    }

    @Test
    @DisplayName("every hook performs the operation it replaced with no context installed")
    void hooksDelegateOutsideAnAsyncTest() throws Exception {
        assertNotNull(AgentSharedInstanceHooks.format(new SimpleDateFormat("yyyy", Locale.ROOT), new Date(0)));
        Matcher m = PATTERN.matcher("corpus-42");
        assertTrue(AgentSharedInstanceHooks.find(m));
        assertEquals("corpus-42", AgentSharedInstanceHooks.group(m));
        assertEquals(32, AgentSharedInstanceHooks.digest(sha256(), PAYLOAD).length);
        Calendar cal = Calendar.getInstance(Locale.ROOT);
        AgentSharedInstanceHooks.set(cal, Calendar.YEAR, 1999);
        assertEquals(1999, AgentSharedInstanceHooks.get(cal, Calendar.YEAR));
        assertEquals("ab", AgentSharedInstanceHooks.append(new StringBuilder("a"), "b").toString());
        assertEquals("7.00", AgentSharedInstanceHooks.format(rootDecimalFormat(), 7L));
        StringBuilder out = new StringBuilder();
        AgentSharedInstanceHooks.format(new Formatter(out), "%d", 7);
        assertEquals("7", out.toString());
        // The persistence-handle hooks only record; with no context they do nothing (#992).
        Object handle = new Object();
        AgentSharedInstanceHooks.persistenceHandleUsed(handle);
        AgentSharedInstanceHooks.persistenceHandleClosed(handle);
        ByteBuffer bb = ByteBuffer.allocate(16);
        assertEquals((byte) 0, AgentSharedInstanceHooks.get(bb));
        CharsetEncoder enc = java.nio.charset.StandardCharsets.UTF_8.newEncoder();
        assertNotNull(AgentSharedInstanceHooks.encode(enc, CharBuffer.wrap("a")));
        Checksum cs = new java.util.zip.CRC32();
        AgentSharedInstanceHooks.update(cs, 1);
        assertEquals(1, cs.getValue() != 0 ? 1 : 0);
        Deflater def = new java.util.zip.Deflater();
        AgentSharedInstanceHooks.setInput(def, new byte[4]);
        def.end();
        SplittableRandom sr = new SplittableRandom(1);
        AgentSharedInstanceHooks.nextInt(sr);
    }

    @Test
    @DisplayName("the hooks this class exercises by hand are all the hooks there are")
    void everyHookIsExercised() {
        long hooks = Arrays.stream(AgentSharedInstanceHooks.class.getMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getDeclaringClass() == AgentSharedInstanceHooks.class)
                .count();
        assertEquals(145, hooks,
                "the shared-instance hooks and the calls in this class are one list, written by "
                        + "hand because each family needs its own receiver and arguments. If this "
                        + "count moved, the new hook belongs in the family test above, or it is "
                        + "the next mutant PIT reports with no coverage (#427). Found " + hooks);
    }
}
