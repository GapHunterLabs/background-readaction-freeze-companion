package dev.gaphunter.backgroundreadactionfreezecompanion.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import dev.gaphunter.backgroundreadactionfreezecompanion.model.BackgroundReadActionFreezeHit
import dev.gaphunter.backgroundreadactionfreezecompanion.review.ReviewPrompt

/**
 * Shared by the Java and the Kotlin inspection: one message format and one
 * review-prompt counter, so both languages report the same way.
 */
object FreezeProblems {

    const val MAX_FILE_LENGTH = 500_000

    fun toProblems(
        file: PsiFile,
        hits: List<BackgroundReadActionFreezeHit>,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (hits.isEmpty()) return null
        val problems = hits.map { hit ->
            manager.createProblemDescriptor(
                hit.anchor,
                messageFor(hit),
                isOnTheFly,
                emptyArray(),
                if (hit.passesCheckCanceled) ProblemHighlightType.WEAK_WARNING else ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            )
        }
        val path = file.virtualFile?.path
        if (path != null) {
            for (hit in hits) {
                val lineNumber = file.viewProvider.document?.getLineNumber(hit.anchor.textRange.startOffset) ?: -1
                ReviewPrompt.recordHit(file.project, "$path:$lineNumber:tier${hit.tier}")
            }
        }
        return problems.toTypedArray()
    }

    /**
     * The sink's own [BackgroundReadActionFreezeHit.sinkReason] is quoted as-is: Tier 2 and the explicitly blocking
     * Tier 1 APIs are never described as "deprecated" -- see
     * [dev.gaphunter.backgroundreadactionfreezecompanion.detect.ReadActionSinkSignals].
     */
    fun messageFor(hit: BackgroundReadActionFreezeHit): String {
        val pathPhrase = if (hit.chain.isEmpty()) "directly" else "via ${hit.chain.joinToString(" -> ")}"
        val base = "${hit.entryPointDescription} reaches ${hit.sinkDisplay} -- ${hit.sinkReason}, $pathPhrase -- can block the " +
            "write lock and freeze the IDE (JetBrains Platform Blog, March 2026: \"many reports actually show problems in " +
            "plugins that contain that single erroneous pattern\")."
        return if (hit.passesCheckCanceled) {
            "$base A ProgressManager/ProgressIndicator#checkCanceled() call was found in the same method, which reduces but does not " +
                "eliminate the risk -- the write lock still blocks until the next poll."
        } else {
            base
        }
    }
}
