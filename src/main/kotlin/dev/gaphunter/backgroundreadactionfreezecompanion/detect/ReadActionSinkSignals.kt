package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.psi.PsiMethodCallExpression

/**
 * One recognized blocking read-lock call: its [tier], the [display] name
 * used in the warning, and the [reason] it is a freeze risk -- each
 * reason worded to match what the real `intellij-community` source says
 * about that exact API, never a blanket "deprecated".
 */
data class SinkMatch(val tier: Int, val display: String, val reason: String)

/**
 * Recognizes a call to the IntelliJ Platform's blocking, non-cancellable
 * read-lock APIs -- verified against the real `intellij-community`
 * source, not assumed from memory:
 *
 * - **Tier 1, deprecated:** `ReadAction.compute()`/`ReadAction.run()`.
 * - **Tier 1, explicitly blocking:** `ReadAction.computeBlocking()`/
 *   `ReadAction.runBlocking()` -- not deprecated, but their own KDoc says
 *   "Avoid usage in background threads as it will likely cause UI
 *   freezes". So the warning quotes that line and never says "deprecated".
 * - **Tier 1:** `DumbService#runReadActionInSmartMode(...)` -- waits for
 *   smart mode, then runs a blocking read action (in current platform
 *   sources, `ReadAction.computeBlocking`).
 * - **Tier 2:** `Application#runReadAction(Runnable|Computable|
 *   ThrowableComputable)` -- NOT formally deprecated, but two of its
 *   three overloads carry the Javadoc line "Avoid using this method
 *   directly in applied/plugins code"; mechanically the same blocking,
 *   non-cancellable read lock underneath. **Never call this Tier 2
 *   "deprecated"** anywhere in this plugin's messages.
 *
 * **Not a sink since 0.3.0:** `ReadAction.computeCancellable()`. It is
 * deprecated, but it delegates to `computeCancellableUnsafe()`, which
 * throws `CannotReadException` when a write action is pending -- it does
 * not block the write lock. Versions 0.1.0-0.2.2 flagged it by mistake.
 */
object ReadActionSinkSignals {

    private const val READ_ACTION_FQN = "com.intellij.openapi.application.ReadAction"
    private const val APPLICATION_FQN = "com.intellij.openapi.application.Application"
    private const val DUMB_SERVICE_FQN = "com.intellij.openapi.project.DumbService"

    const val DEPRECATED_REASON = "deprecated, non-cancellable"
    const val BLOCKING_REASON = "explicitly non-cancellable; its own documentation says " +
        "\"Avoid usage in background threads as it will likely cause UI freezes\""
    const val SMART_MODE_REASON = "non-cancellable: it waits for smart mode, then runs a blocking read action " +
        "(deprecated in current IntelliJ Platform versions)"
    const val KOTLIN_RUN_READ_ACTION_REASON = "non-cancellable (deprecated in current IntelliJ Platform versions, " +
        "where it delegates to runReadActionBlocking)"
    const val TIER2_REASON = "not formally deprecated, but its own Javadoc says " +
        "\"Avoid using this method directly in applied/plugins code\": mechanically the same non-cancellable read lock"

    private val DEPRECATED_NAMES = setOf("compute", "run")
    private val BLOCKING_NAMES = setOf("computeBlocking", "runBlocking")

    fun sinkOf(call: PsiMethodCallExpression): SinkMatch? {
        val resolved = call.resolveMethod() ?: return null
        val className = resolved.containingClass?.qualifiedName ?: return null
        val name = resolved.name
        return when {
            className == READ_ACTION_FQN && name in DEPRECATED_NAMES -> SinkMatch(1, "ReadAction.$name()", DEPRECATED_REASON)
            className == READ_ACTION_FQN && name in BLOCKING_NAMES -> SinkMatch(1, "ReadAction.$name()", BLOCKING_REASON)
            className == DUMB_SERVICE_FQN && name == "runReadActionInSmartMode" ->
                SinkMatch(1, "DumbService#runReadActionInSmartMode(...)", SMART_MODE_REASON)
            className == APPLICATION_FQN && name == "runReadAction" -> SinkMatch(2, "Application#runReadAction(...)", TIER2_REASON)
            else -> null
        }
    }

    /** Returns 1, 2, or null (not a recognized sink) for [call]. */
    fun sinkTierOf(call: PsiMethodCallExpression): Int? = sinkOf(call)?.tier
}
