package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.psi.PsiMethodCallExpression

/**
 * Recognizes a call to the IntelliJ Platform's blocking, non-cancellable
 * read-lock APIs -- split into two tiers verified against the real
 * `intellij-community` source, not assumed from memory:
 *
 * - **Tier 1**: `ReadAction.compute()`/`ReadAction.run()`/
 *   `ReadAction.computeCancellable()` -- all three formally
 *   `@Deprecated`, with the replacement stated in their own Javadoc
 *   (`ReadAction#nonBlocking(...)`).
 * - **Tier 2**: `Application#runReadAction(Runnable|Computable|
 *   ThrowableComputable)` (typically `ApplicationManager.getApplication()
 *   .runReadAction(...)`) -- NOT formally deprecated, but two of its
 *   three overloads carry the Javadoc line "Avoid using this method
 *   directly in applied/plugins code"; mechanically it's the same
 *   blocking, non-cancellable read lock underneath (`ReadAction.compute()`
 *   is itself a thin wrapper over this same call). **Never call this
 *   Tier 2 "deprecated"** anywhere in this plugin's messages -- that
 *   would be an inaccurate claim to a Platform engineer reading real
 *   source, which this plugin's whole credibility depends on getting
 *   right.
 */
object ReadActionSinkSignals {

    private const val READ_ACTION_FQN = "com.intellij.openapi.application.ReadAction"
    private const val APPLICATION_FQN = "com.intellij.openapi.application.Application"

    private val TIER1_METHOD_NAMES = setOf("compute", "run", "computeCancellable")

    /** Returns 1, 2, or null (not a recognized sink) for [call]. */
    fun sinkTierOf(call: PsiMethodCallExpression): Int? {
        val resolved = call.resolveMethod() ?: return null
        val className = resolved.containingClass?.qualifiedName ?: return null
        return when {
            className == READ_ACTION_FQN && resolved.name in TIER1_METHOD_NAMES -> 1
            className == APPLICATION_FQN && resolved.name == "runReadAction" -> 2
            else -> null
        }
    }
}
