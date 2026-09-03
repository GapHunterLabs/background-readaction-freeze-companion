package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.psi.PsiMethod

/** A stable, TEXT-only identity for a method, used as the key of [ProjectReadActionReachabilityAnalyzer]'s cached whole-project summary map -- never the raw [PsiMethod] object across a `CachedValuesManager` cache boundary. Same discipline (and same reasoning) as `log-injection-companion`'s own `MethodKey`. */
object MethodKey {
    fun of(method: PsiMethod): String {
        val className = method.containingClass?.qualifiedName ?: method.containingClass?.name ?: "?"
        val params = method.parameterList.parameters.joinToString(",") { it.type.presentableText }
        return "$className#${method.name}($params)"
    }
}
