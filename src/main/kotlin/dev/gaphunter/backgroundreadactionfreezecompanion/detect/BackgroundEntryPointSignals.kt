package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil

/**
 * Recognizes the four IntelliJ Platform SDK entry points into a
 * background thread this plugin's scope covers -- deliberately a
 * CLOSED list, not "any background work": each entry was picked
 * because it's a real, sanctioned way plugins launch background work
 * per the platform's own docs, not a guess. S4 was added after v0.1's
 * original 3-entry list (S1-S3) proved incomplete against the first
 * real `intellij-community` sample checked during Fase 3 validation
 * (`plugins/hg4idea`) -- see [isBackgroundTaskUtilExecuteOnPooledThreadCall].
 *
 * **Out of scope, stated honestly**: Kotlin coroutines
 * (`Dispatchers.Default`/`IO` -- Kotlin PSI, this catalog's
 * interprocedural machinery is Java-PSI-only), `com.intellij.util.Alarm`,
 * reflection/extension-point invocation, and any `ExecutorService` not
 * obtained from `AppExecutorUtil`. This list grew once already against
 * real code; more real-world gaps of the same shape are plausible.
 */
object BackgroundEntryPointSignals {

    private const val TASK_BACKGROUNDABLE_FQN = "com.intellij.openapi.progress.Task.Backgroundable"
    private const val APPLICATION_FQN = "com.intellij.openapi.application.Application"
    private const val APP_EXECUTOR_UTIL_FQN = "com.intellij.util.concurrency.AppExecutorUtil"
    private const val BACKGROUND_TASK_UTIL_FQN = "com.intellij.util.concurrency.BackgroundTaskUtil"

    /** S1: an override of `run(ProgressIndicator)` in a class whose superclass chain includes `Task.Backgroundable`. */
    fun isBackgroundableRunOverride(method: PsiMethod): Boolean {
        if (method.name != "run") return false
        val params = method.parameterList.parameters
        if (params.size != 1) return false
        if (params[0].type.presentableText != "ProgressIndicator") return false
        val containingClass = method.containingClass ?: return false
        return InheritanceUtil.isInheritor(containingClass, TASK_BACKGROUNDABLE_FQN)
    }

    /** S2: `Application#executeOnPooledThread(Runnable|Callable)` -- true for either overload. */
    fun isExecuteOnPooledThreadCall(call: PsiMethodCallExpression): Boolean {
        if (call.methodExpression.referenceName != "executeOnPooledThread") return false
        val resolved = call.resolveMethod() ?: return false
        return resolved.containingClass?.qualifiedName == APPLICATION_FQN
    }

    /**
     * S3: `ExecutorService#submit/execute` where the receiver resolves
     * to `AppExecutorUtil#getAppExecutorService()`/
     * `getAppScheduledExecutorService()` -- deliberately NOT any
     * `ExecutorService`, to avoid false positives on custom executors
     * whose real threading we don't know. Resolves ONE hop of
     * indirection (a local variable/field initialized directly from
     * that call) since that's a common real pattern (get the pool once,
     * reuse it) -- no deeper alias tracking, same caveat as everywhere
     * else in this plugin: no points-to analysis.
     */
    fun isAppExecutorSubmitCall(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName
        if (methodName != "submit" && methodName != "execute") return false
        val qualifier = call.methodExpression.qualifierExpression ?: return false
        return isAppExecutorServiceExpression(qualifier)
    }

    private fun isAppExecutorServiceExpression(expression: PsiExpression): Boolean {
        if (directAppExecutorUtilCall(expression) != null) return true
        val reference = expression as? PsiReferenceExpression ?: return false
        val variable = reference.resolve() as? PsiVariable ?: return false
        val initializer = variable.initializer ?: return false
        return directAppExecutorUtilCall(initializer) != null
    }

    private fun directAppExecutorUtilCall(expression: PsiExpression): PsiMethodCallExpression? {
        val call = expression as? PsiMethodCallExpression ?: return null
        val resolved = call.resolveMethod() ?: return null
        if (resolved.containingClass?.qualifiedName != APP_EXECUTOR_UTIL_FQN) return null
        if (resolved.name != "getAppExecutorService" && resolved.name != "getAppScheduledExecutorService") return null
        return call
    }

    /**
     * S4: `BackgroundTaskUtil.executeOnPooledThread(Disposable, Runnable)`
     * -- a real, distinct class from `Application#executeOnPooledThread`
     * (S2), NOT part of the original v0.1 closed list. Found during
     * Fase 3 manual validation against real `intellij-community` code
     * (`HgRepositoryImpl.update()`, `plugins/hg4idea`) -- v0.1's
     * 3-entry-point list proved incomplete against real code on the
     * first real sample checked, added here rather than left as a
     * documented-but-unfixed gap.
     */
    fun isBackgroundTaskUtilExecuteOnPooledThreadCall(call: PsiMethodCallExpression): Boolean {
        if (call.methodExpression.referenceName != "executeOnPooledThread") return false
        val resolved = call.resolveMethod() ?: return false
        return resolved.containingClass?.qualifiedName == BACKGROUND_TASK_UTIL_FQN
    }
}
