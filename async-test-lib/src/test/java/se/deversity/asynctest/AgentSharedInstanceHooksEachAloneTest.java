package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shared-instance hook, one at a time, must reach its detector on its own.
 *
 * <p>{@code AgentSharedInstanceHooksTest} drives each family through all of its hooks in one
 * body, which proves the family records but not that every overload does: a hook whose record
 * call was dropped would ride on its siblings' records and the family test would stay green. PIT
 * said exactly that after that test landed (#427): "removed call to record" survived in one
 * overload after another. Here each hook is called alone, on a fresh shared instance and a fresh
 * context, from two threads, and its detector is asked with nothing else recorded.
 */
class AgentSharedInstanceHooksEachAloneTest {

    private static final Pattern PATTERN = Pattern.compile("(?<word>[a-z]+)-(?<num>[0-9]+)");

    private static AsyncTestContext newContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
    }

    /** Two threads at once, each with {@code ctx} installed; what the race throws is ignored. */
    private static void onTwoThreads(AsyncTestContext ctx, ThrowingBody body) {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Runnable sync = () -> {
            AsyncTestContext.install(ctx);
            try {
                barrier.await();
                body.run();
            } catch (Exception raced) { // NOPMD - the race throwing is the bug; the record already happened
                return;
            } finally {
                AsyncTestContext.uninstall();
            }
        };
        Thread a = new Thread(sync, "each-alone-1");
        Thread b = new Thread(sync, "each-alone-2");
        a.start();
        b.start();
        try {
            a.join();
            b.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static <T> T with(AsyncTestContext ctx, Supplier<T> read) {
        AsyncTestContext.install(ctx);
        try {
            return read.get();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /** A fresh receiver of the hook's type, in a state every hook on that type can act on. */
    private static Object receiverFor(Class<?> type) {
        if (type == SimpleDateFormat.class) {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        }
        if (type == Matcher.class) {
            Matcher matcher = PATTERN.matcher("corpus-42");
            matcher.find(); // so that group() has a match to read
            return matcher;
        }
        if (type == MessageDigest.class) {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        if (type == Calendar.class) {
            return Calendar.getInstance(Locale.ROOT);
        }
        if (type == StringBuilder.class) {
            return new StringBuilder();
        }
        if (type == NumberFormat.class) {
            return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        }
        if (type == Formatter.class) {
            return new Formatter(new StringBuilder());
        }
        throw new IllegalArgumentException("no receiver for " + type + ": a new hook family needs one here");
    }

    /** Arguments after the receiver, chosen per receiver type so every hook's call is valid. */
    private static Object[] argumentsFor(Method hook, Object receiver) {
        Class<?>[] types = hook.getParameterTypes();
        Object[] args = new Object[types.length];
        args[0] = receiver;
        for (int i = 1; i < types.length; i++) {
            args[i] = argumentFor(types[i], receiver);
        }
        return args;
    }

    private static Object argumentFor(Class<?> type, Object receiver) {
        if (type == String.class) {
            if (receiver instanceof SimpleDateFormat) {
                return "2026-09-02";
            }
            if (receiver instanceof Matcher) {
                return "word";
            }
            if (receiver instanceof Formatter) {
                return "%s";
            }
            return "s";
        }
        if (type == int.class) {
            // A digest's offset and length must leave room for 32 bytes; everything else
            // (Calendar.YEAR, a matcher group, a builder's int) is happy with 1.
            return receiver instanceof MessageDigest ? 32 : 1;
        }
        if (type == long.class) {
            return 2L;
        }
        if (type == double.class) {
            return 3.0d;
        }
        if (type == char.class) {
            return 'c';
        }
        if (type == boolean.class) {
            return true;
        }
        if (type == byte.class) {
            return (byte) 7;
        }
        if (type == byte[].class) {
            return new byte[64];
        }
        if (type == ByteBuffer.class) {
            return ByteBuffer.wrap(new byte[8]);
        }
        if (type == Date.class) {
            return new Date(0);
        }
        if (type == ParsePosition.class) {
            return new ParsePosition(0);
        }
        if (type == Locale.class) {
            return Locale.ROOT;
        }
        if (type == Object[].class) {
            return new Object[] {"x"};
        }
        if (type == CharSequence.class) {
            return "cs";
        }
        if (type == Object.class) {
            return "o";
        }
        throw new IllegalArgumentException("no argument for " + type + ": a new hook signature needs one here");
    }

    /** Whether the detector for {@code receiver}'s family has a finding, read under {@code ctx}. */
    private static boolean fires(AsyncTestContext ctx, Object receiver) {
        return with(ctx, () -> {
            if (receiver instanceof SimpleDateFormat) {
                return AsyncTestContext.simpleDateFormatDetector().analyze().hasIssues();
            }
            if (receiver instanceof Matcher) {
                return AsyncTestContext.sharedMatcherDetector().analyze().hasIssues();
            }
            if (receiver instanceof MessageDigest) {
                return AsyncTestContext.sharedMessageDigestDetector().analyze().hasIssues();
            }
            if (receiver instanceof Calendar) {
                return AsyncTestContext.calendarDetector().analyze().hasIssues();
            }
            if (receiver instanceof StringBuilder) {
                return AsyncTestContext.stringBuilderDetector().analyze().hasIssues();
            }
            if (receiver instanceof NumberFormat) {
                return AsyncTestContext.sharedDecimalFormatDetector().analyze().hasIssues();
            }
            if (receiver instanceof Formatter) {
                return AsyncTestContext.sharedFormatterDetector().analyze().hasIssues();
            }
            throw new IllegalArgumentException("no detector for " + receiver.getClass());
        });
    }

    private static List<Method> hooks() {
        return Arrays.stream(AgentSharedInstanceHooks.class.getMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getDeclaringClass() == AgentSharedInstanceHooks.class)
                .sorted(Comparator.comparing(Method::toString))
                .toList();
    }

    @Test
    @DisplayName("each shared-instance hook alone, from two threads, reaches its detector")
    void eachHookAloneReachesItsDetector() {
        List<String> silent = new ArrayList<>();
        List<Method> hooks = hooks();
        for (Method hook : hooks) {
            Object receiver = receiverFor(hook.getParameterTypes()[0]);
            Object[] args = argumentsFor(hook, receiver);
            AsyncTestContext ctx = newContext();
            onTwoThreads(ctx, () -> hook.invoke(null, args));
            if (!fires(ctx, receiver)) {
                silent.add(hook.getName() + Arrays.toString(hook.getParameterTypes()));
            }
        }
        assertTrue(hooks.size() >= 33, "the sweep must see every hook; found " + hooks.size());
        assertTrue(silent.isEmpty(),
                "These hooks, called alone on one instance from two threads, recorded nothing their "
                        + "detector could see. A hook that only looks covered because a sibling "
                        + "overload recorded is the mutant PIT reports as surviving (#427): "
                        + silent);
    }

    @Test
    @DisplayName("hooks that return their receiver return that receiver, not a copy")
    void receiverReturningHooksReturnTheReceiver() throws Exception {
        for (Method hook : hooks()) {
            if (hook.getReturnType() != StringBuilder.class && hook.getReturnType() != Formatter.class) {
                continue;
            }
            Object receiver = receiverFor(hook.getParameterTypes()[0]);
            Object result = hook.invoke(null, argumentsFor(hook, receiver));
            assertSame(receiver, result, hook + " must hand back the receiver, as the call it "
                    + "replaced does; anything else silently breaks call chaining in woven code");
        }
    }

    /** A body that may throw whatever the race or the reflective call throws. */
    @FunctionalInterface
    private interface ThrowingBody {
        void run() throws Exception;
    }
}
