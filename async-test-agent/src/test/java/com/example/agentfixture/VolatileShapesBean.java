package com.example.agentfixture;

/**
 * One volatile field of every stack shape the weaver hands to a volatile hook: each primitive, a
 * reference, an array, instance and static, one slot and two.
 *
 * <p>The weaver copies a volatile field's value around the field instruction, and the copy is
 * built differently for each shape. A wrong copy does not produce a wrong answer quietly; it
 * produces a class the verifier refuses, or a field holding the wrong value. {@link #roundTrip}
 * writes and reads every shape, so running it once is the proof.
 */
public class VolatileShapesBean {

    private volatile boolean flag;
    private volatile byte smallByte;
    private volatile char letter;
    private volatile short smallShort;
    private volatile int count;
    private volatile long wide;
    private volatile float ratio;
    private volatile double precise;
    private volatile String name;
    private volatile int[] values;

    private static volatile long staticWide;
    private static volatile double staticPrecise;
    private static volatile int staticCount;
    private static volatile Object staticRef;

    /** {@return every field written, updated through a read, and read back, as one string} */
    public String roundTrip() {
        flag = true;
        smallByte = 3;
        letter = 'x';
        smallShort = 300;
        count = 70_000;
        wide = 1L << 40;
        ratio = 1.5f;
        precise = 2.25;
        name = "n";
        values = new int[] {4};
        staticWide = -1L << 33;
        staticPrecise = -0.5;
        staticCount = -7;
        staticRef = this;
        wide = wide + 1;
        precise = precise * 2;
        staticWide = staticWide - 1;
        staticPrecise = staticPrecise * 4;
        count = count + 1;
        return flag + "," + smallByte + "," + letter + "," + smallShort + "," + count + "," + wide
                + "," + ratio + "," + precise + "," + name + "," + values[0] + "," + staticWide
                + "," + staticPrecise + "," + staticCount + "," + (staticRef == this);
    }
}
