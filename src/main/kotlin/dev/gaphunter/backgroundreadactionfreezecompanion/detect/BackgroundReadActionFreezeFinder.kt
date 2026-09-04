package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import dev.gaphunter.backgroundreadactionfreezecompanion.model.BackgroundReadActionFreezeHit

/**
 * Finds the four IntelliJ Platform SDK background-thread entry points
 * this plugin covers ([BackgroundEntryPointSignals]) and, for each,
 * checks whether it can reach a [ReadActionSinkSignals] call:
 *
 * - S1 (`Task.Backgroundable.run()` override): a real named [PsiMethod]
 *   already tracked by [ProjectReadActionReachabilityAnalyzer]'s
 *   whole-project pass -- look its own summary up directly.
 * - S2/S3/S4 (a lambda/anonymous-class argument): NOT a named project
 *   method the whole-project pass tracks as a graph node, so its body
 *   ([BackgroundArgumentBody]) is scanned ad hoc, against the SAME
 *   summaries map, via [ProjectReadActionReachabilityAnalyzer.reachabilityOfArgumentBody].
 *   The Runnable/Callable argument's position varies by call shape
 *   (`Application#executeOnPooledThread(Runnable)` vs.
 *   `BackgroundTaskUtil.executeOnPooledThread(Disposable, Runnable)`),
 *   so every argument is tried and the first one whose body actually
 *   resolves ([BackgroundArgumentBody.bodyOf] returning non-null) wins
 *   -- never a hardcoded index.
 */
object BackgroundReadActionFreezeFinder {

    fun findAll(file: PsiFile): List<BackgroundReadActionFreezeHit> {
        val project = file.project
        val summaries = ProjectReadActionReachabilityAnalyzer.summariesFor(project)
        val hits = mutableListOf<BackgroundReadActionFreezeHit>()

        file.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitMethod(method: PsiMethod) {
                super.visitMethod(method)
                if (!BackgroundEntryPointSignals.isBackgroundableRunOverride(method)) return
                val summary = summaries[MethodKey.of(method)] ?: return
                hits += toHit(summary, "This Task.Backgroundable.run(ProgressIndicator) override")
            }

            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                super.visitMethodCallExpression(call)
                val description = when {
                    BackgroundEntryPointSignals.isExecuteOnPooledThreadCall(call) -> "This task passed to Application#executeOnPooledThread(...)"
                    BackgroundEntryPointSignals.isAppExecutorSubmitCall(call) -> "This task submitted to AppExecutorUtil's pooled executor"
                    BackgroundEntryPointSignals.isBackgroundTaskUtilExecuteOnPooledThreadCall(call) -> "This task passed to BackgroundTaskUtil#executeOnPooledThread(...)"
                    else -> return
                }
                val body = call.argumentList.expressions.firstNotNullOfOrNull { BackgroundArgumentBody.bodyOf(it) } ?: return
                val summary = ProjectReadActionReachabilityAnalyzer.reachabilityOfArgumentBody(body, summaries) ?: return
                hits += toHit(summary, description)
            }
        })

        return hits
    }

    private fun toHit(summary: ReachabilitySummary, description: String) = BackgroundReadActionFreezeHit(
        anchor = summary.anchor,
        tier = summary.tier,
        chain = summary.chain,
        passesCheckCanceled = summary.passesCheckCanceled,
        entryPointDescription = description,
    )
}
