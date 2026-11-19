package com.example.agentfixture;

/**
 * Field writes and reads whose woven tags depend on which instructions came before them on the
 * same path (#1416): a constant that is not the stored value, a plain write on a path that returns
 * before the volatile write, a volatile read on a path that returns before the plain read.
 */
@SuppressWarnings({"unused", "PMD"})
public class FlowSensitiveFieldSample {

    public static int a;
    public static int b;
    public static int c;

    public Object[] array;
    public int plain;
    public volatile int flag;

    /** {@code ICONST_1; ANEWARRAY}: the 1 is the length, not the stored value. */
    public void storeArray() {
        array = new Object[1];
    }

    /** The 1 stored in {@code a} must not tag the store to {@code b}, which stores {@code c}. */
    public static void copyAfterConstant() {
        a = 1;
        b = c;
    }

    /** A plain write on a path that returns before any volatile write is not published. */
    public void writeThenReturnEarly(boolean early) {
        if (early) {
            plain = 1;
            return;
        }
        flag = 1;
    }

    /** A plain read on a path that never read the volatile is not ordered by it. */
    public int readAfterEarlyVolatileRead(boolean early) {
        if (early) {
            return flag;
        }
        return plain;
    }

    /** The idiom: written, then published by the volatile write, on one path. */
    public void publish(int value) {
        plain = value;
        flag = 1;
    }

    /** The idiom's read side across a join every path of which read the volatile first. */
    public int memoized(int value) {
        if (flag == 0) {
            synchronized (this) {
                if (flag == 0) {
                    plain = value;
                    flag = 1;
                }
            }
        }
        return plain;
    }
}
