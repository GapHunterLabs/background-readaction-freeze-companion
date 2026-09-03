package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * A method's own "can reach a [ReadActionSinkSignals] call" summary --
 * [tier] (1 or 2), [chain] of callee display names from THIS method
 * down to (but not including) the method that directly contains the
 * sink call, [anchor] the call site INSIDE this method's own body that
 * starts the chain (the sink call itself when [chain] is empty,
 * otherwise the call to the first callee), and [passesCheckCanceled]
 * -- true when the method that directly contains the sink call ALSO
 * contains a [CheckCanceledSignals] call somewhere in its own body.
 */
data class ReachabilitySummary(
    val tier: Int,
    val anchor: PsiElement,
    val chain: List<String>,
    val passesCheckCanceled: Boolean,
)

/**
 * Whole-project, SCC-ordered fixed-point computation of which project
 * methods can reach a [ReadActionSinkSignals] call -- either directly,
 * or transitively through a call to another project method whose own
 * summary already proves the same thing. Same Tarjan-SCC-then-fixed-point
 * structure as `log-injection-companion`'s `ProjectLogTaintAnalyzer` and
 * `interprocedural-resource-leak-companion`'s
 * `ProjectResourceCloseSummaryAnalyzer`, applied to a simpler lattice:
 * one reachability summary per method (or none), not per parameter --
 * the property being tracked here is not data flowing through
 * arguments, it's simply "can executing this method's body reach the
 * sink at all".
 *
 * **v0.1 scope, stated honestly:** only project methods (a call into a
 * compiled library/dependency, including any platform SDK method
 * beyond the [ReadActionSinkSignals]/[BackgroundEntryPointSignals]
 * signatures this plugin knows about, terminates that branch --
 * assumed unknown, never inferred); a virtual call resolves to a
 * single candidate via `call.resolveMethod()` -- no CHA, no
 * points-to, same caveat already accepted catalog-wide; a project with
 * more than [MAX_METHODS] total analyzable methods skips analysis
 * entirely rather than risk pathological cost.
 */
object ProjectReadActionReachabilityAnalyzer {

    const val MAX_METHODS = 3000
    private const val MAX_FILE_LENGTH = 500_000

    private val CACHE_KEY: Key<CachedValue<Map<String, ReachabilitySummary>>> = Key.create("backgroundReadActionFreezeCompanion.summaries")

    fun summariesFor(project: Project): Map<String, ReachabilitySummary> {
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            { CachedValueProvider.Result.create(computeSummaries(project), PsiModificationTracker.MODIFICATION_COUNT) },
            false,
        )
    }

    /**
     * Public entry used by `BackgroundReadActionFreezeFinder` for an S2/S3
     * lambda/anonymous-class argument body that is NOT itself a named
     * project method the whole-project pass tracks -- resolved against
     * the same [summariesFor] map, by [MethodKey].
     */
    fun reachabilityOfArgumentBody(body: PsiElement, summaries: Map<String, ReachabilitySummary>): ReachabilitySummary? =
        reachabilityInBody(body) { callee -> summaries[MethodKey.of(callee)] }

    private fun computeSummaries(project: Project): Map<String, ReachabilitySummary> {
        val scope = GlobalSearchScope.projectScope(project)
        val files = FilenameIndex.getAllFilesByExt(project, "java", scope)
        val psiManager = PsiManager.getInstance(project)

        val allMethods = mutableListOf<PsiMethod>()
        for (virtualFile in files) {
            val psiFile = psiManager.findFile(virtualFile) as? PsiJavaFile ?: continue
            if (psiFile.text.length > MAX_FILE_LENGTH) continue
            psiFile.accept(object : JavaRecursiveElementWalkingVisitor() {
                override fun visitMethod(method: PsiMethod) {
                    super.visitMethod(method)
                    if (method.body != null) allMethods += method
                }
            })
        }
        if (allMethods.size > MAX_METHODS) return emptyMap()

        val methodSet = allMethods.toHashSet()
        val graph: Map<PsiMethod, List<PsiMethod>> = allMethods.associateWith { method -> calleesOf(method, methodSet) }
        val sccsCalleesFirst = TarjanSccComputer(graph).compute()

        val summaries = HashMap<PsiMethod, ReachabilitySummary>()
        for (scc in sccsCalleesFirst) {
            var changed = true
            while (changed) {
                changed = false
                for (method in scc) {
                    val previous = summaries[method]
                    val recomputed = summaryForMethod(method, summaries)
                    if (recomputed != null && recomputed != previous) {
                        summaries[method] = recomputed
                        changed = true
                    }
                }
            }
        }

        return summaries.entries.associate { (method, summary) -> MethodKey.of(method) to summary }
    }

    private fun calleesOf(method: PsiMethod, methodSet: Set<PsiMethod>): List<PsiMethod> {
        val body = method.body ?: return emptyList()
        val callees = mutableListOf<PsiMethod>()
        body.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                super.visitMethodCallExpression(call)
                val resolved = call.resolveMethod() ?: return
                if (resolved in methodSet) callees += resolved
            }
        })
        return callees
    }

    private fun summaryForMethod(method: PsiMethod, summaries: Map<PsiMethod, ReachabilitySummary>): ReachabilitySummary? {
        val body = method.body ?: return null
        return reachabilityInBody(body) { callee -> summaries[callee] }
    }

    /** Shared by the whole-project fixed point ([summaryForMethod], keyed by [PsiMethod]) and the ad-hoc S2/S3 lambda scan ([reachabilityOfArgumentBody], keyed by [MethodKey] string) -- only the callee lookup strategy differs. */
    private fun reachabilityInBody(body: PsiElement, resolveSummary: (PsiMethod) -> ReachabilitySummary?): ReachabilitySummary? {
        var result: ReachabilitySummary? = null
        body.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                if (result != null) return
                super.visitMethodCallExpression(call)
                if (result != null) return

                val tier = ReadActionSinkSignals.sinkTierOf(call)
                if (tier != null) {
                    result = ReachabilitySummary(
                        tier = tier,
                        anchor = call.methodExpression.referenceNameElement ?: call.methodExpression,
                        chain = emptyList(),
                        passesCheckCanceled = CheckCanceledSignals.containsCheckCanceled(body),
                    )
                    return
                }

                val callee = call.resolveMethod() ?: return
                val calleeSummary = resolveSummary(callee) ?: return
                result = calleeSummary.copy(
                    anchor = call.methodExpression.referenceNameElement ?: call.methodExpression,
                    chain = listOf(calleeDisplayName(callee)) + calleeSummary.chain,
                )
            }
        })
        return result
    }

    private fun calleeDisplayName(method: PsiMethod): String {
        val className = method.containingClass?.name
        return if (className != null) "$className.${method.name}()" else "${method.name}()"
    }
}
