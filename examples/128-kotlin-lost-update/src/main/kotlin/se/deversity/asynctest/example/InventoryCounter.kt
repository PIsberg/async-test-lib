package se.deversity.asynctest.example

/**
 * A counter with a lost update, written the way it usually appears in Kotlin.
 *
 * `var` on a class property looks safe: there is no visible assignment expression, no obvious
 * read-modify-write, and Kotlin's null-safety and immutability defaults create a general sense
 * that the language is looking after you. It is not looking after this. `count++` compiles to a
 * read, an add and a write, exactly as in Java, and two threads that interleave those three steps
 * lose one of the increments.
 */
class InventoryCounter {

    /** The bug. `@Volatile` would fix visibility and still lose updates; only atomicity helps. */
    var count: Int = 0
        private set

    fun record() {
        count++
    }
}
