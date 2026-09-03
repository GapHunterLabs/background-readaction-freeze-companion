import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;

/**
 * Demo file for Background ReadAction Freeze Companion -- each method
 * below demonstrates one case the inspection recognizes. Open this file
 * in the sandbox IDE with the plugin installed to see all of them
 * highlighted (or correctly left alone) at once.
 */
class DirectHitTask extends Task.Backgroundable {
    DirectHitTask(Project project) {
        super(project, "Direct hit -- Tier 1");
    }

    @Override
    public void run(ProgressIndicator indicator) {
        // Tier 1: ReadAction.compute() is formally @Deprecated and non-cancellable.
        ReadAction.compute(() -> resolveSymbols());
    }

    private Object resolveSymbols() {
        return null;
    }
}

class ChainedHitTask extends Task.Backgroundable {
    ChainedHitTask(Project project) {
        super(project, "Chained hit -- reached through a helper");
    }

    @Override
    public void run(ProgressIndicator indicator) {
        collectUsages();
    }

    private void collectUsages() {
        // The hit is reported on run()'s call to collectUsages(), naming
        // this method in the chain -- not buried silently in here.
        ReadAction.run(() -> scanIndex());
    }

    private void scanIndex() {
    }
}

class DowngradedHitTask extends Task.Backgroundable {
    DowngradedHitTask(Project project) {
        super(project, "Downgraded -- checkCanceled() already present");
    }

    @Override
    public void run(ProgressIndicator indicator) {
        analyzeWithCancellationCheck();
    }

    private void analyzeWithCancellationCheck() {
        // Still flagged (the write lock can still block), but as a
        // weaker warning -- this method already shows partial awareness
        // of the freeze risk.
        ProgressManager.checkCanceled();
        ReadAction.compute(() -> resolveType());
    }

    private Object resolveType() {
        return null;
    }
}

class Tier2PooledThreadDemo {
    void launch() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // Tier 2: NOT formally @Deprecated, but its own Javadoc says
            // "Avoid using this method directly in applied/plugins code" --
            // mechanically the same non-cancellable read lock.
            ApplicationManager.getApplication().runReadAction(() -> {
                validateModel();
            });
        });
    }

    private void validateModel() {
    }
}

class CleanTask extends Task.Backgroundable {
    CleanTask(Project project) {
        super(project, "Clean -- uses the sanctioned cancellable replacement");
    }

    @Override
    public void run(ProgressIndicator indicator) {
        // Not flagged: ReadAction.nonBlocking() is the cancellable,
        // sanctioned replacement -- exactly what the platform's own
        // 2026.* migration guidance recommends.
        ReadAction.nonBlocking(() -> resolveSymbols()).submit(Runnable::run);
    }

    private Object resolveSymbols() {
        return null;
    }
}
