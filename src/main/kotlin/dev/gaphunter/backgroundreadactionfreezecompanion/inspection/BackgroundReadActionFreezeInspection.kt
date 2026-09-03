package dev.gaphunter.backgroundreadactionfreezecompanion.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import dev.gaphunter.backgroundreadactionfreezecompanion.detect.BackgroundReadActionFreezeFinder
import dev.gaphunter.backgroundreadactionfreezecompanion.model.BackgroundReadActionFreezeHit
import dev.gaphunter.backgroundreadactionfreezecompanion.review.ReviewPrompt

/** Flags a background-thread entry point that can reach a non-cancellable ReadAction sink -- see [BackgroundReadActionFreezeFinder]. */
class BackgroundReadActionFreezeInspection : LocalInspectionTool() {

    companion object {
        const val MAX_FILE_LENGTH = 500_000
    }

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file.text.length > MAX_FILE_LENGTH) return null

        val hits = BackgroundReadActionFreezeFinder.findAll(file)
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

    /** Tier 2 is never described as "deprecated" -- see [dev.gaphunter.backgroundreadactionfreezecompanion.detect.ReadActionSinkSignals]'s own doc for why that distinction matters here specifically. */
    private fun messageFor(hit: BackgroundReadActionFreezeHit): String {
        val tierPhrase = if (hit.tier == 1) {
            "ReadAction.compute()/run()/computeCancellable() -- deprecated, non-cancellable"
        } else {
            "Application#runReadAction(...) -- not formally deprecated, but its own Javadoc says " +
                "\"Avoid using this method directly in applied/plugins code\": mechanically the same non-cancellable read lock"
        }
        val pathPhrase = if (hit.chain.isEmpty()) "directly" else "via ${hit.chain.joinToString(" -> ")}"
        val base = "${hit.entryPointDescription} reaches $tierPhrase, $pathPhrase -- can block the write lock and freeze the IDE " +
            "(JetBrains Platform Blog, March 2026: \"many reports actually show problems in plugins that contain that single erroneous pattern\")."
        return if (hit.passesCheckCanceled) {
            "$base A ProgressManager/ProgressIndicator#checkCanceled() call was found in the same method, which reduces but does not " +
                "eliminate the risk -- the write lock still blocks until the next poll."
        } else {
            base
        }
    }
}
