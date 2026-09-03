package dev.gaphunter.backgroundreadactionfreezecompanion.inspection

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The light `BasePlatformTestCase` project has no library on its
 * classpath -- not even the IntelliJ Platform SDK's own classes,
 * unlike the host JVM actually running this test. Every other plugin
 * in this catalog avoids this entirely (their sinks are JDK types or
 * name-heuristics, never `com.intellij.*` itself) -- this plugin is
 * the first whose SUBJECT code references the platform SDK, so
 * [addPlatformStubs] adds minimal, self-contained stand-ins for the
 * handful of real signatures this plugin's detectors match against
 * (verified against the real `intellij-community` source, see
 * `ReadActionSinkSignals`/`BackgroundEntryPointSignals`). Only the
 * qualified class name + method name/arity matter to this plugin's own
 * checks -- functional-interface parameter types are simplified to
 * plain JDK ones (`Runnable`/`Callable<T>`) rather than reproducing the
 * platform's real `ThrowableComputable`/`ThrowableRunnable`.
 */
class BackgroundReadActionFreezeInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(BackgroundReadActionFreezeInspection::class.java)
        addPlatformStubs()
    }

    private fun addPlatformStubs() {
        myFixture.addFileToProject(
            "com/intellij/openapi/project/Project.java",
            """
            package com.intellij.openapi.project;
            public interface Project {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/intellij/openapi/progress/ProgressIndicator.java",
            """
            package com.intellij.openapi.progress;
            public interface ProgressIndicator {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/intellij/openapi/progress/ProgressManager.java",
            """
            package com.intellij.openapi.progress;
            public class ProgressManager {
                public static void checkCanceled() {
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/intellij/openapi/progress/ProgressIndicatorProvider.java",
            """
            package com.intellij.openapi.progress;
            public class ProgressIndicatorProvider {
                public static void checkCanceled() {
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/intellij/openapi/progress/Task.java",
            """
            package com.intellij.openapi.progress;

            import com.intellij.openapi.project.Project;

            public abstract class Task {
                public Task(Project project, String title) {
                }

                public abstract static class Backgroundable extends Task {
                    public Backgroundable(Project project, String title) {
                        super(project, title);
                    }

                    public abstract void run(ProgressIndicator indicator);
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/intellij/openapi/application/ReadAction.java",
            """
            package com.intellij.openapi.application;

            import java.util.concurrent.Callable;

            public class ReadAction {
                public static <T> T compute(Callable<T> action) {
                    return null;
                }

                public static void run(Runnable action) {
                }

                public static <T> T computeCancellable(Callable<T> action) {
                    return null;
                }

                public static <T> T nonBlocking(Callable<T> action) {
                    return null;
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/intellij/openapi/application/Application.java",
            """
            package com.intellij.openapi.application;

            public interface Application {
                void executeOnPooledThread(Runnable runnable);
                void runReadAction(Runnable runnable);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/intellij/openapi/application/ApplicationManager.java",
            """
            package com.intellij.openapi.application;

            public class ApplicationManager {
                public static Application getApplication() {
                    return null;
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/intellij/util/concurrency/AppExecutorUtil.java",
            """
            package com.intellij.util.concurrency;

            import java.util.concurrent.ExecutorService;

            public class AppExecutorUtil {
                public static ExecutorService getAppExecutorService() {
                    return null;
                }

                public static ExecutorService getAppScheduledExecutorService() {
                    return null;
                }
            }
            """.trimIndent(),
        )
    }

    fun `test a Task Backgroundable run override calling ReadAction compute directly is flagged tier 1`() {
        myFixture.configureByText(
            "BackgroundTask1.java",
            """
            import com.intellij.openapi.application.ReadAction;
            import com.intellij.openapi.progress.ProgressIndicator;
            import com.intellij.openapi.progress.Task;
            import com.intellij.openapi.project.Project;

            class BackgroundTask1 extends Task.Backgroundable {
                BackgroundTask1(Project project) {
                    super(project, "Task 1");
                }

                @Override
                public void run(ProgressIndicator indicator) {
                    ReadAction.compute(() -> 1);
                }
            }
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("ReadAction.compute()") == true && it.description?.contains("directly") == true })
    }

    fun `test a Task Backgroundable run override reaching ReadAction run via a helper is flagged through the interprocedural fixed point`() {
        myFixture.configureByText(
            "BackgroundTask2.java",
            """
            import com.intellij.openapi.application.ReadAction;
            import com.intellij.openapi.progress.ProgressIndicator;
            import com.intellij.openapi.progress.Task;
            import com.intellij.openapi.project.Project;

            class BackgroundTask2 extends Task.Backgroundable {
                BackgroundTask2(Project project) {
                    super(project, "Task 2");
                }

                @Override
                public void run(ProgressIndicator indicator) {
                    helper();
                }

                private void helper() {
                    ReadAction.run(() -> { });
                }
            }
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        // The chain entry is qualified as "ClassName.methodName()" (see `calleeDisplayName`), not bare "helper()".
        assertTrue(highlights.any { it.description?.contains("via BackgroundTask2.helper()") == true })
    }

    fun `test ReadAction compute with no reachable background entry point is not flagged`() {
        myFixture.configureByText(
            "NotBackground3.java",
            """
            import com.intellij.openapi.application.ReadAction;

            class NotBackground3 {
                void doWork() {
                    ReadAction.compute(() -> 1);
                }
            }
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("ReadAction") == true })
    }

    fun `test a Task Backgroundable run override using the sanctioned ReadAction nonBlocking replacement is not flagged`() {
        myFixture.configureByText(
            "BackgroundTask4.java",
            """
            import com.intellij.openapi.application.ReadAction;
            import com.intellij.openapi.progress.ProgressIndicator;
            import com.intellij.openapi.progress.Task;
            import com.intellij.openapi.project.Project;

            class BackgroundTask4 extends Task.Backgroundable {
                BackgroundTask4(Project project) {
                    super(project, "Task 4");
                }

                @Override
                public void run(ProgressIndicator indicator) {
                    ReadAction.nonBlocking(() -> 1);
                }
            }
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("ReadAction") == true })
    }

    fun `test a task passed to executeOnPooledThread reaching Application runReadAction is flagged tier 2 without calling it deprecated`() {
        myFixture.configureByText(
            "Launcher5.java",
            """
            import com.intellij.openapi.application.ApplicationManager;

            class Launcher5 {
                void launch() {
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        ApplicationManager.getApplication().runReadAction(() -> {
                            doSomething();
                        });
                    });
                }

                private void doSomething() {
                }
            }
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        val hit = highlights.firstOrNull { it.description?.contains("Application#runReadAction") == true }
        assertNotNull(hit)
        assertFalse(hit!!.description!!.contains("deprecated, non-cancellable"))
    }

    fun `test a helper shared between a background entry point and a non-background method is flagged only once`() {
        myFixture.configureByText(
            "Launcher6.java",
            """
            import com.intellij.openapi.application.ReadAction;
            import com.intellij.openapi.progress.ProgressIndicator;
            import com.intellij.openapi.progress.Task;
            import com.intellij.openapi.project.Project;

            class Launcher6 extends Task.Backgroundable {
                Launcher6(Project project) {
                    super(project, "Task 6");
                }

                @Override
                public void run(ProgressIndicator indicator) {
                    sharedHelper();
                }

                void notBackground() {
                    sharedHelper();
                }

                private void sharedHelper() {
                    ReadAction.compute(() -> 1);
                }
            }
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        val hits = highlights.filter { it.description?.contains("reaches") == true }
        assertEquals(1, hits.size)
    }

    fun `test a helper that also calls checkCanceled is flagged as a weaker warning instead of suppressed`() {
        myFixture.configureByText(
            "Launcher7.java",
            """
            import com.intellij.openapi.application.ReadAction;
            import com.intellij.openapi.progress.ProgressIndicator;
            import com.intellij.openapi.progress.ProgressManager;
            import com.intellij.openapi.progress.Task;
            import com.intellij.openapi.project.Project;

            class Launcher7 extends Task.Backgroundable {
                Launcher7(Project project) {
                    super(project, "Task 7");
                }

                @Override
                public void run(ProgressIndicator indicator) {
                    helper();
                }

                private void helper() {
                    ProgressManager.checkCanceled();
                    ReadAction.compute(() -> 1);
                }
            }
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
        assertTrue(highlights.any { it.description?.contains("checkCanceled() call was found") == true })
    }

    fun `test the reported message names the intermediate helper in the call chain`() {
        myFixture.configureByText(
            "Launcher8.java",
            """
            import com.intellij.openapi.application.ReadAction;
            import com.intellij.openapi.progress.ProgressIndicator;
            import com.intellij.openapi.progress.Task;
            import com.intellij.openapi.project.Project;

            class Launcher8 extends Task.Backgroundable {
                Launcher8(Project project) {
                    super(project, "Task 8");
                }

                @Override
                public void run(ProgressIndicator indicator) {
                    helperMethod();
                }

                private void helperMethod() {
                    ReadAction.run(() -> { });
                }
            }
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("helperMethod()") == true })
    }

    fun `test a task submitted to a pool obtained one hop earlier from AppExecutorUtil reaching ReadAction compute is flagged`() {
        myFixture.configureByText(
            "Launcher9.java",
            """
            import com.intellij.openapi.application.ReadAction;
            import com.intellij.util.concurrency.AppExecutorUtil;
            import java.util.concurrent.ExecutorService;

            class Launcher9 {
                void launch() {
                    ExecutorService pool = AppExecutorUtil.getAppExecutorService();
                    pool.submit(() -> {
                        ReadAction.compute(() -> 1);
                    });
                }
            }
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("AppExecutorUtil's pooled executor") == true })
    }
}
