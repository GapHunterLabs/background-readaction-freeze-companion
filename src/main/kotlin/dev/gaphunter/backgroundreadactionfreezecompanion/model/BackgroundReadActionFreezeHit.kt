package dev.gaphunter.backgroundreadactionfreezecompanion.model

import com.intellij.psi.PsiElement

/**
 * A confirmed hit: [entryPointDescription] (a background-thread entry
 * point this plugin recognizes) can reach a [tier] 1 or 2 sink, either
 * directly ([chain] empty) or via the given [chain] of callee display
 * names. [anchor] is the call site, inside the entry point's OWN body,
 * where the chain starts. [passesCheckCanceled] is true when the
 * method directly containing the sink call also calls `checkCanceled()`
 * somewhere in its own body (downgrades severity, never suppresses).
 */
data class BackgroundReadActionFreezeHit(
    val anchor: PsiElement,
    val tier: Int,
    val chain: List<String>,
    val passesCheckCanceled: Boolean,
    val entryPointDescription: String,
)
