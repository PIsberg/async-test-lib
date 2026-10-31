package se.deversity.asynctest.fixture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.extension.AsyncTestExtension
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

/**
 * An `@AsyncTest` written as a `suspend fun` is refused with a message that names the fix (#1146).
 *
 * The runner cannot call a suspending function: it has no coroutine to resume it with. JUnit 6
 * answers such a method in one of two ways, and neither says that. Without `kotlin-reflect`, which
 * this module does not have, discovery of the whole class fails with "requires
 * org.jetbrains.kotlin:kotlin-reflect", which sends the user off to add it. With it, JUnit accepts
 * the method and every worker fails with `wrong number of arguments: 0 expected: 1`. Both were
 * measured against JUnit 6.1.3 before this test existed.
 *
 * The first path runs through the real launcher, which loads the library's discovery listener;
 * the second calls the extension the way JUnit does once it has accepted the method.
 */
class KotlinSuspendAsyncTestFailsTest {

    @Test
    fun suspendAsyncTestFailsDiscoveryWithTheFix() {
        RAN.set(0)
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClass(SuspendingBody::class.java))
            .configurationParameter("license.mock.mode", "true")
            .build()
        val listener = SummaryGeneratingListener()
        // JUnit's own resolution failure for the class is critical, so the launcher throws out of
        // execute() rather than recording a failure; Surefire and Gradle print that throwable and
        // its causes. Either shape counts, as long as the run fails and says why.
        val thrown = runCatching { LauncherFactory.create().execute(request, listener) }.exceptionOrNull()
        // With execute() thrown out of, the listener never started and has no summary to give.
        val recorded = if (thrown == null) listener.summary.failures.map { it.exception } else emptyList()
        val failures = recorded + listOfNotNull(thrown)
        val messages = failures.flatMap { generateSequence(it) { t -> t.cause }.toList() }
            .joinToString("\n") { it.toString() }

        assertEquals(0, RAN.get(), "a suspend @AsyncTest body ran after all; the premise changed")
        assertTrue(failures.isNotEmpty(), "a suspend @AsyncTest failed nothing")
        assertTrue(messages.contains("SuspendingBody#body") && messages.contains("suspend fun"),
            "the failure must name the method and say it is a suspend fun: $messages")
        assertTrue(messages.contains("runBlocking"), "the failure must name the fix: $messages")
    }

    @Test
    fun extensionRefusesASuspendMethodJUnitAccepted() {
        val method = SuspendingBody::class.java.declaredMethods.single { it.name == "body" }
        val context = Proxy.newProxyInstance(
            ExtensionContext::class.java.classLoader, arrayOf(ExtensionContext::class.java)
        ) { _, called, _ ->
            when (called.name) {
                "getTestMethod" -> Optional.of(method)
                "getTestClass" -> Optional.of(SuspendingBody::class.java)
                else -> throw UnsupportedOperationException(called.name)
            }
        } as ExtensionContext

        val refused = assertThrows(Exception::class.java) {
            AsyncTestExtension().provideTestTemplateInvocationContexts(context).count()
        }
        assertTrue(refused.message.orEmpty().contains("suspend fun")
                && refused.message.orEmpty().contains("runBlocking"),
            "the extension must refuse the method and name the fix: $refused")
    }

    class SuspendingBody {
        @AsyncTest(threads = 1, invocations = 1, licenseMockMode = true)
        suspend fun body() {
            RAN.incrementAndGet()
        }
    }

    companion object {
        val RAN = AtomicInteger()
    }
}
