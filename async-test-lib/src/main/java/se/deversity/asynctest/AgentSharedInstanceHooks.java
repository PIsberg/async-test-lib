package se.deversity.asynctest;

import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;

import se.deversity.asynctest.diagnostics.SharedMatcherDetector;
import se.deversity.asynctest.diagnostics.SharedMessageDigestDetector;
import se.deversity.asynctest.diagnostics.SimpleDateFormatDetector;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for JDK types that are not thread safe and carry no way to say so.
 *
 * <h2>Why these three</h2>
 *
 * <p>{@code SimpleDateFormat}, {@code Matcher} and {@code MessageDigest} share a shape:
 * each keeps mutable parsing or digest state inside the instance, each is documented as unsafe for
 * concurrent use, and each is routinely cached in a static field because constructing one is
 * expensive. That combination is one of the oldest concurrency bugs in Java, and until now this
 * library could only see it if the test author called a {@code record} method by hand, which means
 * seeing it only when they already suspected it.
 *
 * <p>The mechanism is the one {@link AgentCollectionHooks} documents: substituting the invocation
 * with a static hook whose first parameter is the receiver hands the instance over without spilling
 * arguments, so the operand stack shape is unchanged and no member is added.
 *
 * <h2>Why the shared-instance family and not every stateful JDK type</h2>
 *
 * <p>Each entry costs a rewritten instruction in every woven method that calls it, so the table is
 * not a place to be generous. These three earn it by being both common and unambiguous: unlike
 * {@code Random}, none of them has a thread-safe subclass that a call site could be holding, so a
 * substituted call cannot be recording an instance that was safe all along. {@code ThreadLocalRandom}
 * is exactly that hazard, which is why {@code Random} is absent here.
 *
 * <p>Sharing is decided by the detector, not by this class. A hook fires on every call, and the
 * detector reports only when it has seen more than one thread touch the same instance, so a
 * confined formatter used a thousand times costs a map lookup and produces nothing.
 */
@AIContract(reason = "Called from bytecode the agent rewrites: the method names and erased signatures here are matched by CollectionAccessWeaver.SHARED_INSTANCE_ENTRIES and cannot change independently of it. Every hook must perform the original operation and propagate its exceptions unchanged, and must record before delegating only where the original cannot throw first - the detector's question is 'did two threads touch this instance', which a call that threw still answers. The receiver types are deliberately concrete and free of thread-safe subclasses: adding one that has a safe subclass, Random being the standing example with ThreadLocalRandom, turns every substituted call site into a potential false positive on correct code.")
public final class AgentSharedInstanceHooks {

    private AgentSharedInstanceHooks() {
    }

    /**
     * Weaves {@code SimpleDateFormat.format(Date)}.
     *
     * @param receiver the formatter
     * @param date     the date to format
     * @return the formatted text
     */
    public static String format(SimpleDateFormat receiver, Date date) {
        SimpleDateFormatDetector detector = AsyncTestContext.currentSimpleDateFormatDetector();
        if (detector != null) {
            detector.recordFormat(receiver, receiver.getClass().getName());
        }
        return receiver.format(date);
    }

    /**
     * Weaves {@code SimpleDateFormat.parse(String)}.
     *
     * @param receiver the formatter
     * @param source   the text to parse
     * @return the parsed date
     * @throws ParseException if the text cannot be parsed
     */
    public static Date parse(SimpleDateFormat receiver, String source) throws ParseException {
        SimpleDateFormatDetector detector = AsyncTestContext.currentSimpleDateFormatDetector();
        if (detector != null) {
            detector.recordParse(receiver, receiver.getClass().getName());
        }
        return receiver.parse(source);
    }

    /**
     * Weaves {@code Matcher.find()}.
     *
     * @param receiver the matcher
     * @return whether a match was found
     */
    public static boolean find(Matcher receiver) {
        recordMatcher(receiver);
        return receiver.find();
    }

    /**
     * Weaves {@code Matcher.matches()}.
     *
     * @param receiver the matcher
     * @return whether the whole region matched
     */
    public static boolean matches(Matcher receiver) {
        recordMatcher(receiver);
        return receiver.matches();
    }

    /**
     * Weaves {@code Matcher.group()}.
     *
     * @param receiver the matcher
     * @return the matched subsequence
     */
    public static String group(Matcher receiver) {
        recordMatcher(receiver);
        return receiver.group();
    }

    private static void recordMatcher(Matcher receiver) {
        SharedMatcherDetector detector = AsyncTestContext.currentSharedMatcherDetector();
        if (detector != null) {
            detector.recordAccess(receiver, receiver.getClass().getName(), Thread.currentThread());
        }
    }

    /**
     * Weaves {@code MessageDigest.update(byte[])}.
     *
     * @param receiver the digest
     * @param input    the bytes to add
     */
    public static void update(MessageDigest receiver, byte[] input) {
        recordDigest(receiver);
        receiver.update(input);
    }

    /**
     * Weaves {@code MessageDigest.digest()}.
     *
     * @param receiver the digest
     * @return the computed hash
     */
    public static byte[] digest(MessageDigest receiver) {
        recordDigest(receiver);
        return receiver.digest();
    }

    /**
     * Weaves {@code MessageDigest.digest(byte[])}.
     *
     * @param receiver the digest
     * @param input    the final bytes to add before computing
     * @return the computed hash
     */
    public static byte[] digest(MessageDigest receiver, byte[] input) {
        recordDigest(receiver);
        return receiver.digest(input);
    }

    private static void recordDigest(MessageDigest receiver) {
        SharedMessageDigestDetector detector =
                AsyncTestContext.currentSharedMessageDigestDetector();
        if (detector != null) {
            // getAlgorithm() is the label a reader wants and the instance already holds it, so
            // this costs a field read rather than the string concatenation a prettier name would.
            detector.recordAccess(receiver, receiver.getAlgorithm(), Thread.currentThread());
        }
    }
}
