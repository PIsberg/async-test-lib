package com.example.agentfixture;

/**
 * An object handed from one thread to another through a field read and written under one
 * monitor, for #1247.
 *
 * <p>The writer fills {@code prepared} and stores it in {@code mail} under {@code lock}; a reader
 * that loads {@code mail} under the same lock and gets that object has it only because the store
 * put it there, so the store's critical section precedes the load's in every schedule in which
 * the reader holds it, and the reader may use the object's plain fields outside the monitor.
 */
public class GuardedHandOffBean {

    /** The object handed over. */
    public static class Mail {
        public int body;
    }

    private final Object lock = new Object();

    private final Object otherLock = new Object();

    private Mail mail;

    private final Mail prepared = new Mail();

    /** Fills the mail, then hands it over under the lock. */
    public void handOff() {
        prepared.body = prepared.body + 1;
        synchronized (lock) {
            mail = prepared;
        }
    }

    /** Hands the mail over, then keeps writing it: the write after the hand-off is unordered. */
    public void handOffThenWrite() {
        handOff();
        prepared.body = prepared.body + 1;
    }

    /** Fills the mail, then hands it over in a synchronized method of this object. */
    public void handOffInASynchronizedMethod() {
        prepared.body = prepared.body + 1;
        store();
    }

    private synchronized void store() {
        mail = prepared;
    }

    private synchronized Mail load() {
        return mail;
    }

    /** Takes the mail under the lock and updates it outside the monitor. */
    public int bumpTakenUnderTheLock() {
        Mail taken;
        synchronized (lock) {
            taken = mail;
        }
        return bump(taken);
    }

    /** Takes the mail with no monitor held: nothing orders the update after the hand-off. */
    public int bumpTakenWithoutTheLock() {
        return bump(mail);
    }

    /** Takes the mail under a lock the writer never held: that orders nothing either. */
    public int bumpTakenUnderAnotherLock() {
        Mail taken;
        synchronized (otherLock) {
            taken = mail;
        }
        return bump(taken);
    }

    /** Takes the mail through a synchronized method of this object. */
    public int bumpTakenInASynchronizedMethod() {
        return bump(load());
    }

    private static int bump(Mail taken) {
        if (taken == null) {
            return -1;
        }
        taken.body = taken.body + 1;
        return taken.body;
    }
}
