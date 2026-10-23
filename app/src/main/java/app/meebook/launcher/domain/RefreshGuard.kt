package app.meebook.launcher.domain

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.supervisorScope

/** Pure decisions / helpers behind the home refresh. */
object RefreshGuard {
    /** The date shown on home ([shown], from the last load) is no longer today's date. */
    fun dayChanged(shown: LocalDate, now: LocalDate): Boolean = shown != now

    /**
     * Runs [body] in a supervisor scope, so a failing child (an async load) surfaces only where it
     * is awaited and is caught here. Returns true when [body] completed, false when it failed
     * with an [Exception] (reported to [onFailure]). Cancellation is rethrown, never swallowed.
     */
    suspend fun run(onFailure: (Exception) -> Unit, body: suspend CoroutineScope.() -> Unit): Boolean =
        try {
            supervisorScope(body)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e)
            false
        }
}
