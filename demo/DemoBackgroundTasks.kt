import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Demo file for the Kotlin side of Background ReadAction Freeze Companion -- syntactic (text/imports, no type
 * resolution), so each case below shows up even in a loose file with no platform SDK on the classpath. Mirrors
 * DemoBackgroundTasks.java one case at a time.
 */
class DirectHitTaskKt(project: Project) : Task.Backgroundable(project, "Direct hit -- Tier 1") {
    override fun run(indicator: ProgressIndicator) {
        // Tier 1: ReadAction.compute() is formally @Deprecated and non-cancellable.
        ReadAction.compute<Any?, Nothing> { resolveSymbols() }
    }

    private fun resolveSymbols(): Any? = null
}

class ChainedHitTaskKt(project: Project) : Task.Backgroundable(project, "Chained hit -- reached through a helper") {
    override fun run(indicator: ProgressIndicator) {
        // The hit is reported on run()'s call to collectUsages(), naming this method in the chain.
        collectUsages()
    }

    private fun collectUsages() {
        ReadAction.run<Nothing> { scanIndex() }
    }

    private fun scanIndex() {}
}

class DowngradedHitTaskKt(project: Project) : Task.Backgroundable(project, "Downgraded -- checkCanceled() already present") {
    override fun run(indicator: ProgressIndicator) {
        analyzeWithCancellationCheck()
    }

    private fun analyzeWithCancellationCheck() {
        // Still flagged (the write lock can still block), but as a weaker warning: this method already shows
        // partial awareness of the freeze risk.
        ProgressManager.checkCanceled()
        ReadAction.compute<Any?, Nothing> { resolveType() }
    }

    private fun resolveType(): Any? = null
}

class Tier2PooledThreadDemoKt {
    // Tier 2: not formally @Deprecated, but its own Javadoc says "Avoid using this method directly in
    // applied/plugins code" -- mechanically the same non-cancellable read lock. Reached through a helper, like
    // ChainedHitTaskKt, so the entry point's lambda never nests another lambda inside it.
    fun launch() {
        ApplicationManager.getApplication().executeOnPooledThread { readModel() }
    }

    private fun readModel() {
        ApplicationManager.getApplication().runReadAction { validateModel() }
    }

    private fun validateModel() {}
}

class CleanTaskKt(project: Project) : Task.Backgroundable(project, "Clean -- uses the sanctioned cancellable replacement") {
    override fun run(indicator: ProgressIndicator) {
        // Not flagged: ReadAction.nonBlocking() is the cancellable, sanctioned replacement.
        ReadAction.nonBlocking<Any?> { resolveSymbols() }.submit(Runnable::run)
    }

    private fun resolveSymbols(): Any? = null
}
