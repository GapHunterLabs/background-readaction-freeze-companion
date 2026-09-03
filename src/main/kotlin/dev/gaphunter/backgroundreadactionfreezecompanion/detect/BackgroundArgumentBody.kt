package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiNewExpression

/**
 * Extracts the body to scan from an argument passed to a [S2]/[S3]
 * background entry point call -- a lambda expression's own body, or an
 * anonymous `Runnable`/`Callable` class's `run()`/`call()` method body.
 *
 * **Deliberately excludes method references** (`this::doWork`) in
 * v0.1 -- a real gap, declared out of scope rather than half-handled.
 */
object BackgroundArgumentBody {
    fun bodyOf(argument: PsiExpression): PsiElement? = when (argument) {
        is PsiLambdaExpression -> argument.body
        is PsiNewExpression -> {
            val anonymousClass = argument.anonymousClass
            anonymousClass?.methods?.firstOrNull { it.name == "run" || it.name == "call" }?.body
        }
        else -> null
    }
}
