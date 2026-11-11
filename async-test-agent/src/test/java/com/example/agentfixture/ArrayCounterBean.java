package com.example.agentfixture;

/**
 * Counters and buffers kept in plain arrays, the shapes #1250 found silent: a lost update on an
 * array element drew nothing while the same update on a field was reported.
 */
public final class ArrayCounterBean {

    private ArrayCounterBean() {
    }

    /** Every thread bumps the one shared slot: a lost update. */
    public static void bumpShared(int[] counter) {
        counter[0]++;
    }

    /** The shared bump written inside a lambda body, a synthetic method. */
    public static void bumpSharedInALambda(int[] counter) {
        Runnable bump = () -> counter[0]++;
        bump.run();
    }

    /** A two-slot element, so the woven store shuffles a {@code long} value. */
    public static void bumpSharedLong(long[] histogram) {
        histogram[0] += 2L;
    }

    /** Each thread its own slot: correct, and must stay silent. */
    public static void bumpOwnSlot(int[] slots, int slot) {
        slots[slot]++;
    }

    /** The shared slot bumped under one lock: correct. */
    public static void bumpUnderLock(int[] counter, Object lock) {
        synchronized (lock) {
            counter[0]++;
        }
    }

    /** Sums a {@code double[]} element by element, for the woven-code-computes-the-same check. */
    public static double fillAndSum(double[] values) {
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 1.5d;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum;
    }

    /** Copies references through an {@code Object[]} and returns the element at {@code at}. */
    public static Object storeAndLoad(Object[] slots, Object value, int at) {
        slots[at] = value;
        return slots[at];
    }

    /** Reads every primitive kind once, so each load opcode is woven and run. */
    public static long readEveryKind(byte[] b, char[] c, short[] s, float[] f, boolean[] z) {
        b[0] = 7;
        c[0] = 'x';
        s[0] = 300;
        f[0] = 2.5f;
        z[0] = true;
        return b[0] + c[0] + s[0] + (long) f[0] + (z[0] ? 1 : 0);
    }
}
