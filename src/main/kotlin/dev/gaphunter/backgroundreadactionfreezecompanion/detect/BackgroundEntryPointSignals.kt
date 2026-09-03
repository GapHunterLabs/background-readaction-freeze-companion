package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil

/**
 * Recognizes the three IntelliJ Platform SDK entry points into a
 * background thread this plugin's v0.1 scope covers -- deliberately a
 * CLOSED list, not "any background work": each entry was picked
 * because it's a real, sanctioned way plugins launch background work
 * per the platform's own docs, not a guess.
 *
 * **Out of scope, stated honestly**: Kotlin coroutines
 * (`Dispatchers.Default`/`IO` -- Kotlin PSI, this catalog's
 * interprocedural machinery is Java-PSI-only), `com.intellij.util.Alarm`,
 * reflection/extension-point invocation, and any `ExecutorService` not
 * obtained from `AppExecutorUtil`.
 */
object BackgroundEntryPointSignals {

    private const val TASK_BACKGROUNDABLE_FQN = "com.intellij.openapi.progress.Task.Backgroundable"
    private const val APPLICATION_FQN = "com.intellij.openapi.application.Application"
    private const val APP_EXECUTOR_UTIL_FQN = "com.intellij.util.concurrency.AppExecutorUtil"

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
}
