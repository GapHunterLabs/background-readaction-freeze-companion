package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethodCallExpression

/**
 * Detects an explicit `checkCanceled()` call anywhere in a given body
 * (`ProgressManager#checkCanceled()`, `ProgressIndicatorProvider
 * #checkCanceled()`, or an instance `ProgressIndicator#checkCanceled()`
 * call) -- used to downgrade, never suppress, a
 * [ReadActionSinkSignals] hit: a dev who already added a cancellation
 * check showed partial awareness of the freeze risk, but the write
 * lock still blocks until the next poll, so the hit still needs
 * reporting, just at lower severity.
 *
 * **Order-agnostic on purpose**: this only checks whether the call
 * exists ANYWHERE in the same method body, not whether it textually
 * precedes the sink call -- this plugin's path-sensitivity budget,
 * like the rest of the catalog's AST-structural engines, does not
 * extend to real statement ordering within a single method.
 */
object CheckCanceledSignals {

    private val CHECK_CANCELED_CONTAINING_CLASSES = setOf(
        "com.intellij.openapi.progress.ProgressManager",
        "com.intellij.openapi.progress.ProgressIndicatorProvider",
        "com.intellij.openapi.progress.ProgressIndicator",
    )

    fun containsCheckCanceled(body: PsiElement): Boolean {
        var found = false
        body.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                if (found) return
                super.visitMethodCallExpression(call)
                if (found) return
                if (call.methodExpression.referenceName != "checkCanceled") return
                val resolved = call.resolveMethod() ?: return
                if (resolved.containingClass?.qualifiedName in CHECK_CANCELED_CONTAINING_CLASSES) found = true
            }
        })
        return found
    }
}
