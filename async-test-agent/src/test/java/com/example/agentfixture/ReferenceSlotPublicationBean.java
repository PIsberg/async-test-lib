package com.example.agentfixture;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * A plain update published through a reference slot that is not an {@code AtomicReference}, for
 * #741's last item: an {@code AtomicReferenceFieldUpdater}, an {@code AtomicReferenceArray}, a
 * {@code VarHandle} on an instance field and one on an array element.
 *
 * <p>Each {@code publishThroughX} updates {@code data} and then stores a token into the slot.
 * Each {@code bumpAfterX} reads the slot with an acquiring read and, when it returned the token,
 * updates {@code data}: ordered after the writer. {@link #bumpWithoutReading} updates it with no
 * read of any slot: not ordered.
 */
public class ReferenceSlotPublicationBean {

    private static final AtomicReferenceFieldUpdater<ReferenceSlotPublicationBean, Object> SLOT =
            AtomicReferenceFieldUpdater.newUpdater(ReferenceSlotPublicationBean.class, Object.class, "slot");

    private static final VarHandle HANDLE;
    private static final VarHandle ELEMENTS = MethodHandles.arrayElementVarHandle(Object[].class);

    static {
        try {
            HANDLE = MethodHandles.lookup().findVarHandle(ReferenceSlotPublicationBean.class, "handled", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public int data;

    private volatile Object slot;

    private volatile Object handled;

    private final AtomicReferenceArray<Object> array = new AtomicReferenceArray<>(4);

    private final Object[] elements = new Object[4];

    public void publishThroughUpdater() {
        data = data + 1;
        SLOT.set(this, "token");
    }

    public int bumpAfterUpdater() {
        return SLOT.get(this) == null ? -1 : bump();
    }

    public void publishThroughArray() {
        data = data + 1;
        array.set(2, "token");
    }

    public int bumpAfterArray() {
        return array.get(2) == null ? -1 : bump();
    }

    public void publishThroughHandle() {
        data = data + 1;
        HANDLE.setRelease(this, (Object) "token");
    }

    public int bumpAfterHandle() {
        Object token = (Object) HANDLE.getAcquire(this);
        return token == null ? -1 : bump();
    }

    public void publishThroughElementHandle() {
        data = data + 1;
        ELEMENTS.setVolatile(elements, 1, (Object) "token");
    }

    public int bumpAfterElementHandle() {
        Object token = (Object) ELEMENTS.getVolatile(elements, 1);
        return token == null ? -1 : bump();
    }

    public int bumpWithoutReading() {
        return bump();
    }

    private int bump() {
        data = data + 1;
        return data;
    }
}
