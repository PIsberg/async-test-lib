package com.example.agentfixture;

import jakarta.persistence.EntityManager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Every field width the weavers copy around, in the expression shapes javac compiles into the
 * stack layouts that are hard to copy (#1147).
 *
 * <p>A {@code long} or {@code double} is one value in two stack slots, so the instruction that
 * copies or moves it is not the one that copies an {@code int}: {@code DUP} on half a long, or a
 * {@code SWAP} across one, is a {@code VerifyError}. Each method below puts a wide value somewhere a
 * weaver inserts code: under a receiver for {@code PUTFIELD}, as the result of an assignment that is
 * itself used ({@code DUP2_X1} before the store), inside a {@code synchronized} body, a loop and a
 * handler, and in constructors. {@link #exercise} returns every field afterwards, so a woven copy
 * that verifies but moves a value is caught too: its answer differs from the unwoven class's.
 */
public class WideOperandSample {

    static long staticLong;
    static double staticDouble;
    static volatile long staticVolatileLong;
    static volatile double staticVolatileDouble;
    static volatile int staticVolatileInt;
    static volatile Object staticVolatileRef;

    boolean z;
    byte b;
    char c;
    short s;
    int i;
    long j;
    float f;
    double d;
    Object ref;
    long[] longs = new long[2];
    double[] doubles = new double[2];

    volatile boolean vz;
    volatile byte vb;
    volatile char vc;
    volatile short vs;
    volatile int vi;
    volatile long vj;
    volatile float vf;
    volatile double vd;
    volatile Object vref;

    private final List<String> trace = new ArrayList<>();

    /** A wide value stored in a constructor, before and after a {@code this(...)} delegation. */
    public WideOperandSample() {
        this(7L, 0.5);
        vj += 1;
    }

    WideOperandSample(long seedLong, double seedDouble) {
        super();
        vj = seedLong;
        vd = seedDouble;
        j = vj;
        d = vd;
    }

    /**
     * Runs every shape and returns every field, in a fixed order.
     *
     * @param x a long the shapes combine
     * @param y a double the shapes combine
     * @return the fields and the intermediate values, as text
     */
    public String exercise(long x, double y) {
        WideOperandSample other = new WideOperandSample(x, y);

        // Compound assignment: GETFIELD under a DUP of the receiver, then PUTFIELD of a wide value.
        j += x;
        d *= y;
        vj += x;
        vd -= y;
        staticLong++;
        staticDouble += 1.5;
        staticVolatileLong = staticVolatileLong * 3 + x;
        staticVolatileDouble += vd;

        // Chained and value-returning stores: the stored value is DUP2_X1'd or DUP2'd before
        // each store so the next one can use it.
        j = vj = staticLong = staticVolatileLong = x + 1;
        d = vd = staticDouble = staticVolatileDouble = y * 2;
        record(storeAndReturn(x * 5), stored(y / 4));

        // Pre and post increment on wide volatile fields inside one expression.
        long both = vj++ + ++vj;
        double mixed = vd++ - --vd;
        trace.add("both=" + both + " mixed=" + mixed);

        // Every narrow width too, volatile and not.
        z = !z;
        vz = z;
        b = (byte) (b + 3);
        vb += b;
        c = 'q';
        vc = (char) (c + 1);
        s = (short) (s - 2);
        vs = s;
        i = (int) x;
        vi += i;
        staticVolatileInt = vi;
        f = (float) y;
        vf = f * 2;
        ref = "r" + x;
        vref = ref;
        staticVolatileRef = vref;

        // Array elements and another object's field: the receiver is not this.
        longs[0] += vj;
        longs[1] = longs[0]++;
        doubles[0] += vd;
        other.vj = this.vj + other.vj;
        other.vd = this.vd * other.vd;
        other.j = other.vj;
        this.j = other.j += x;

        // A conditional that leaves a wide value on the stack across a join.
        vj = x > 0 ? vj : staticVolatileLong;
        vd = y > 0 ? vd : staticVolatileDouble;

        // A lambda reading and writing wide fields from a synthetic method.
        LongSupplier supplier = () -> vj += staticVolatileLong;
        long supplied = supplier.getAsLong();

        // Persistence-handle calls with a wide value already on the stack beneath the receiver
        // and its arguments, for the zero, one and two-argument copies.
        long persisted = persistenceShapes(x);

        // Monitor entry and exit around wide stores, a loop back edge, and a handler.
        guardedAdd(x);
        for (int k = 0; k < 3; k++) {
            vj += k;
            vd += k;
        }
        try {
            vj = Math.addExact(vj, x);
            vd = vd / (x - x + 1);
            longs[(int) (x % 3)] = vj; // index 2 throws on purpose for x = 2 and 5
        } catch (ArrayIndexOutOfBoundsException expected) {
            vj -= 1;
        }

        return trace + " supplied=" + supplied + " persisted=" + persisted
                + " z=" + z + " b=" + b + " c=" + c + " s=" + s + " i=" + i + " j=" + j
                + " f=" + f + " d=" + d + " ref=" + ref
                + " vz=" + vz + " vb=" + vb + " vc=" + vc + " vs=" + vs + " vi=" + vi
                + " vj=" + vj + " vf=" + vf + " vd=" + vd + " vref=" + vref
                + " longs=" + longs[0] + "," + longs[1] + " doubles=" + doubles[0]
                + " other=" + other.vj + "," + other.vd + "," + other.j
                + " statics=" + staticLong + "," + staticDouble + "," + staticVolatileLong + ","
                + staticVolatileDouble + "," + staticVolatileInt + "," + staticVolatileRef;
    }

    /** Resets the statics so two loads of the class start from the same state. */
    public static void resetStatics() {
        staticLong = 0;
        staticDouble = 0;
        staticVolatileLong = 0;
        staticVolatileDouble = 0;
        staticVolatileInt = 0;
        staticVolatileRef = null;
    }

    private long persistenceShapes(long x) {
        EntityManager em = (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(), new Class<?>[] {EntityManager.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.TRUE : null);
        long none = vj + (em.isOpen() ? 1L : 0L);
        long one = vj + (em.contains(ref) ? 2L : 0L);
        double two = vd + (em.find(Long.class, x) == null ? 3.0 : 0.0);
        return none + one + (long) two;
    }

    private long storeAndReturn(long value) {
        return vj = value;
    }

    private double stored(double value) {
        return staticVolatileDouble = vd = value;
    }

    private void record(long l, double dv) {
        trace.add("l=" + l + " d=" + dv);
    }

    private synchronized void guardedAdd(long x) {
        vj += x;
        vd += x;
        j = vj;
    }
}
