package com.example.agentfixture;

import java.util.concurrent.locks.Lock;

/**
 * Guards a counter with the two views of a read-write lock it was handed, obtained by code outside
 * the woven packages, as views handed out before an attach or by an unwoven library are (#1498).
 */
public class HandedViewsBean {

    private final Lock read;
    private final Lock write;
    private int count;

    public HandedViewsBean(Lock read, Lock write) {
        this.read = read;
        this.write = write;
    }

    public void increment() {
        write.lock();
        try {
            count++;
        } finally {
            write.unlock();
        }
    }

    public int current() {
        read.lock();
        try {
            return count;
        } finally {
            read.unlock();
        }
    }

    /** The bug: a write under the read view, which admits every other reader. */
    public void incrementUnderRead() {
        read.lock();
        try {
            count++;
        } finally {
            read.unlock();
        }
    }
}
