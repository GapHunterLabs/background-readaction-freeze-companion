package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.gaphunter.backgroundreadactionfreezecompanion.model.BackgroundReadActionFreezeHit
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Kotlin counterpart of [BackgroundReadActionFreezeFinder] (0.3.0).
 *
 * **Syntactic on purpose**, like this catalog's other Kotlin finders: the platform APIs are matched by name, receiver
 * text and imports, not by type resolution, so the result is the same in K1 and K2 mode. To keep precision:
 * `ReadAction.*` only counts when the file imports `com.intellij.openapi.application.ReadAction` (or the whole
 * package), and the top-level `runReadAction {}`/`runReadActionBlocking {}` only count when imported from
 * `com.intellij.openapi.application` -- a project's own function with the same name is never flagged.
 *
 * Entry points: a `Task.Backgroundable` `run(ProgressIndicator)` override (class or `object :` literal), a function
 * annotated `@RequiresBackgroundThread`, and the tasks passed to `executeOnPooledThread`, `AppExecutorUtil`'s
 * executors and `runBackgroundableTask`. Code inside `invokeLater`, write actions, `ReadAction.nonBlocking`,
 * `readAction {}` or `withContext(Dispatchers.EDT)` does not run on that background thread and is skipped.
 *
 * **Stated honestly:** calls are followed only into functions of the SAME file (by name, up to [MAX_DEPTH] levels).
 * A Kotlin call into another file, or into Java code, is not followed. Coroutine entry points are out of scope
 * (JetBrains' own DevKit already reports blocking calls in suspend contexts).
 */
object KotlinBackgroundReadActionFinder {

    private const val APPLICATION_PACKAGE = "com.intellij.openapi.application"
    private const val MAX_DEPTH = 4

    private val BACKGROUNDABLE = Regex("""\bBackgroundable\b""")
    private val EDT_DISPATCHER = Regex("""Dispatchers\.(EDT|Main|UI)\b""")
    private val APPLICATION_RECEIVER = Regex("""(?i)application|\bapp\b""")
    private val CHECK_CANCELED_NAMES = setOf("checkCanceled", "checkCancelled", "ensureActive")
    private val SWITCH_NAMES = setOf(
        "invokeLater", "invokeAndWait", "invokeLaterIfNeeded", "runInEdt", "runWriteAction", "writeAction",
        "edtWriteAction", "runWriteActionAndWait", "nonBlocking", "readAction", "smartReadAction",
        "constrainedReadAction", "readAndWriteAction", "readAndEdtWriteAction", "writeIntentReadAction",
    )

    private class Context(val functionsByName: Map<String, List<KtNamedFunction>>, val imports: Set<String>)

    private class Reach(val chain: List<String>, val sink: SinkMatch, val passesCheckCanceled: Boolean)

    fun findAll(file: PsiFile): List<BackgroundReadActionFreezeHit> {
        if (file !is KtFile) return emptyList()
        val functions = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
        val imports = file.importDirectives.mapNotNull { directive ->
            val fqName = directive.importedFqName?.asString() ?: return@mapNotNull null
            if (directive.isAllUnder) "$fqName.*" else fqName
        }.toMutableSet()
        if (file.packageFqName.asString() == APPLICATION_PACKAGE) imports += "$APPLICATION_PACKAGE.*"
        val ctx = Context(functions.filter { it.name != null }.groupBy { it.name!! }, imports)
        val hits = LinkedHashMap<PsiElement, BackgroundReadActionFreezeHit>()

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                val description = when {
                    isBackgroundableRun(function) -> "This Task.Backgroundable.run(ProgressIndicator) override"
                    function.annotationEntries.any { it.shortName?.asString() == "RequiresBackgroundThread" } ->
                        "This @RequiresBackgroundThread function"
                    else -> return
                }
                function.bodyExpression?.let { scan(ctx, it, description, hits) }
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                val description = entryDescription(expression) ?: return
                for (body in backgroundBodies(ctx, expression)) scan(ctx, body, description, hits)
            }
        })
        return hits.values.toList()
    }

    private fun scan(ctx: Context, body: KtElement, description: String, hits: MutableMap<PsiElement, BackgroundReadActionFreezeHit>) {
        for (call in callsIn(body)) {
            if (isSwitched(call, body)) continue
            val callee = call.calleeExpression ?: continue
            val sink = sinkOf(ctx, call)
            val reach = when {
                sink != null -> Reach(emptyList(), sink, containsCheckCanceled(body))
                receiverText(call).let { it == null || it == "this" } -> reachThroughHelpers(ctx, callee.text, 1, mutableSetOf())
                else -> null
            } ?: continue
            hits.putIfAbsent(
                callee,
                BackgroundReadActionFreezeHit(
                    anchor = callee,
                    tier = reach.sink.tier,
                    chain = reach.chain,
                    passesCheckCanceled = reach.passesCheckCanceled,
                    entryPointDescription = description,
                    sinkDisplay = reach.sink.display,
                    sinkReason = reach.sink.reason,
                ),
            )
        }
    }

    /** A same-file function named [name] that can reach a sink, directly or through more same-file functions. */
    private fun reachThroughHelpers(ctx: Context, name: String, depth: Int, visited: MutableSet<KtNamedFunction>): Reach? {
        for (function in ctx.functionsByName[name].orEmpty()) {
            if (!visited.add(function)) continue
            val body = function.bodyExpression ?: continue
            val display = displayOf(function)
            for (call in callsIn(body)) {
                if (isSwitched(call, body)) continue
                val sink = sinkOf(ctx, call)
                if (sink != null) return Reach(listOf(display), sink, containsCheckCanceled(body))
            }
            if (depth >= MAX_DEPTH) continue
            for (call in callsIn(body)) {
                if (isSwitched(call, body) || receiverText(call).let { it != null && it != "this" }) continue
                val inner = reachThroughHelpers(ctx, call.calleeExpression?.text ?: continue, depth + 1, visited) ?: continue
                return Reach(listOf(display) + inner.chain, inner.sink, inner.passesCheckCanceled)
            }
        }
        return null
    }

    private fun sinkOf(ctx: Context, call: KtCallExpression): SinkMatch? {
        val name = call.calleeExpression?.text ?: return null
        val receiver = receiverText(call)
        val readAction = receiver != null && isPlatformReadAction(ctx, receiver)
        return when {
            readAction && name in setOf("compute", "run") ->
                SinkMatch(1, "ReadAction.$name()", ReadActionSinkSignals.DEPRECATED_REASON)
            readAction && name in setOf("computeBlocking", "runBlocking") ->
                SinkMatch(1, "ReadAction.$name()", ReadActionSinkSignals.BLOCKING_REASON)
            receiver != null && name == "runReadActionInSmartMode" ->
                SinkMatch(1, "DumbService#runReadActionInSmartMode(...)", ReadActionSinkSignals.SMART_MODE_REASON)
            receiver == null && name == "runReadActionBlocking" && importedFromApplication(ctx, name) ->
                SinkMatch(1, "runReadActionBlocking {}", ReadActionSinkSignals.BLOCKING_REASON)
            receiver == null && name == "runReadAction" && importedFromApplication(ctx, name) ->
                SinkMatch(1, "runReadAction {}", ReadActionSinkSignals.KOTLIN_RUN_READ_ACTION_REASON)
            receiver != null && name == "runReadAction" && APPLICATION_RECEIVER.containsMatchIn(receiver) ->
                SinkMatch(2, "Application#runReadAction(...)", ReadActionSinkSignals.TIER2_REASON)
            else -> null
        }
    }

    private fun isPlatformReadAction(ctx: Context, receiver: String): Boolean = when (receiver) {
        "$APPLICATION_PACKAGE.ReadAction" -> true
        "ReadAction" -> importedFromApplication(ctx, "ReadAction")
        else -> false
    }

    private fun importedFromApplication(ctx: Context, name: String): Boolean =
        "$APPLICATION_PACKAGE.$name" in ctx.imports || "$APPLICATION_PACKAGE.*" in ctx.imports

    private fun entryDescription(call: KtCallExpression): String? {
        val name = call.calleeExpression?.text ?: return null
        val receiver = receiverText(call).orEmpty()
        return when {
            name == "executeOnPooledThread" && receiver.contains("BackgroundTaskUtil") ->
                "This task passed to BackgroundTaskUtil#executeOnPooledThread(...)"
            name == "executeOnPooledThread" -> "This task passed to Application#executeOnPooledThread(...)"
            (name == "submit" || name == "execute") && receiver.contains("AppExecutorUtil") ->
                "This task submitted to AppExecutorUtil's pooled executor"
            name == "runBackgroundableTask" -> "This task passed to runBackgroundableTask(...)"
            else -> null
        }
    }

    /** Lambda bodies, `Runnable { }` SAM bodies, `object : Runnable` bodies and `::function` references among the arguments. */
    private fun backgroundBodies(ctx: Context, call: KtCallExpression): List<KtElement> {
        val bodies = mutableListOf<KtElement>()
        for (argument in call.valueArguments) {
            when (val expression = argument.getArgumentExpression()) {
                is KtLambdaExpression -> expression.bodyExpression?.let(bodies::add)
                is KtCallExpression -> expression.lambdaArguments.mapNotNullTo(bodies) { it.getLambdaExpression()?.bodyExpression }
                is KtObjectLiteralExpression -> expression.objectDeclaration.declarations
                    .filterIsInstance<KtNamedFunction>()
                    .filter { it.name == "run" || it.name == "call" }
                    .mapNotNullTo(bodies) { it.bodyExpression }
                is KtCallableReferenceExpression ->
                    ctx.functionsByName[expression.callableReference.text].orEmpty().mapNotNullTo(bodies) { it.bodyExpression }
                else -> {}
            }
        }
        return bodies
    }

    private fun isBackgroundableRun(function: KtNamedFunction): Boolean {
        if (function.name != "run" || !function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return false
        val parameter = function.valueParameters.singleOrNull() ?: return false
        if (parameter.typeReference?.text?.contains("ProgressIndicator") != true) return false
        val owner = function.containingClassOrObject ?: return false
        return owner.superTypeListEntries.any { BACKGROUNDABLE.containsMatchIn(it.text) }
    }

    /** True when [call] sits inside a lambda passed to an EDT, write-action or cancellable-read call below [stop]. */
    private fun isSwitched(call: KtCallExpression, stop: PsiElement): Boolean {
        var current: PsiElement? = call.parent
        while (current != null && current != stop) {
            if (current is KtCallExpression) {
                val name = current.calleeExpression?.text
                if (name in SWITCH_NAMES) return true
                if (name == "withContext" &&
                    EDT_DISPATCHER.containsMatchIn(current.valueArguments.firstOrNull()?.text.orEmpty())
                ) return true
            }
            current = current.parent
        }
        return false
    }

    private fun receiverText(call: KtCallExpression): String? {
        val parent = call.parent as? KtQualifiedExpression ?: return null
        return if (parent.selectorExpression == call) parent.receiverExpression.text else null
    }

    /**
     * Every call in [element], INCLUDING [element] itself: an expression-bodied function (`fun f() = runReadAction { }`)
     * has the call as its whole body, and `PsiTreeUtil.findChildrenOfType` alone skips the root.
     */
    private fun callsIn(element: PsiElement): Collection<KtCallExpression> =
        PsiTreeUtil.findChildrenOfAnyType(element, false, KtCallExpression::class.java)

    private fun containsCheckCanceled(element: PsiElement): Boolean =
        callsIn(element).any { it.calleeExpression?.text in CHECK_CANCELED_NAMES }

    private fun displayOf(function: KtNamedFunction): String {
        val owner = function.containingClassOrObject?.name
        return if (owner != null) "$owner.${function.name}()" else "${function.name}()"
    }
}
