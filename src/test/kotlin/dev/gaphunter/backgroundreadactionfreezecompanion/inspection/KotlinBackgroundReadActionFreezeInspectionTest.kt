package dev.gaphunter.backgroundreadactionfreezecompanion.inspection

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The Kotlin finder is syntactic (name, receiver text and imports), so these fixtures need no platform stubs: an
 * unresolved import does not change what the finder sees. The shapes mirror real code: [testApplicationRunReadAction]
 * is the exact shape this catalog's own Refactor Simulator shipped in `ImpactPanel.kt` before 2026.2.3.
 */
class KotlinBackgroundReadActionFreezeInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KotlinBackgroundReadActionFreezeInspection::class.java)
    }

    private fun freezeWarnings(severity: HighlightSeverity = HighlightSeverity.WARNING): List<HighlightInfo> =
        myFixture.doHighlighting(severity).filter { it.description?.contains("can block the write lock") == true }

    fun `test a Task Backgroundable subclass calling the imported runReadAction is flagged tier 1`() {
        myFixture.configureByText(
            "IndexTask.kt",
            """
            import com.intellij.openapi.application.runReadAction
            import com.intellij.openapi.progress.ProgressIndicator
            import com.intellij.openapi.progress.Task
            import com.intellij.openapi.project.Project

            class IndexTask(project: Project) : Task.Backgroundable(project, "Index") {
                override fun run(indicator: ProgressIndicator) {
                    val count = runReadAction { 42 }
                }
            }
            """.trimIndent(),
        )
        val hit = freezeWarnings().singleOrNull()
        assertNotNull(hit)
        assertTrue(hit!!.description!!.contains("runReadAction {}"))
        assertTrue(hit.description!!.contains("Task.Backgroundable.run(ProgressIndicator)"))
    }

    fun `test an object literal Task Backgroundable calling ReadAction compute is flagged`() {
        myFixture.configureByText(
            "Launcher.kt",
            """
            import com.intellij.openapi.application.ReadAction
            import com.intellij.openapi.progress.ProgressIndicator
            import com.intellij.openapi.progress.ProgressManager
            import com.intellij.openapi.progress.Task
            import com.intellij.openapi.project.Project

            fun launch(project: Project) {
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scan") {
                    override fun run(indicator: ProgressIndicator) {
                        ReadAction.compute<Int, Throwable> { 1 }
                    }
                })
            }
            """.trimIndent(),
        )
        assertTrue(freezeWarnings().any { it.description!!.contains("ReadAction.compute()") })
    }

    fun testApplicationRunReadAction() {
        myFixture.configureByText(
            "ImpactPanel.kt",
            """
            import com.intellij.openapi.application.ApplicationManager
            import com.intellij.openapi.progress.ProgressIndicator
            import com.intellij.openapi.progress.ProgressManager
            import com.intellij.openapi.progress.Task
            import com.intellij.openapi.project.Project

            class ImpactPanel(private val project: Project) {
                fun simulate() {
                    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Simulating") {
                        override fun run(indicator: ProgressIndicator) {
                            val roots = ApplicationManager.getApplication().runReadAction<List<String>> { emptyList() }
                        }
                    })
                }
            }
            """.trimIndent(),
        )
        val hit = freezeWarnings().singleOrNull()
        assertNotNull(hit)
        assertTrue(hit!!.description!!.contains("Application#runReadAction"))
        assertFalse(hit.description!!.contains("deprecated, non-cancellable"))
    }

    fun `test a pooled thread task reaching runReadActionBlocking through a same-file helper names the helper`() {
        myFixture.configureByText(
            "Refresher.kt",
            """
            import com.intellij.openapi.application.ApplicationManager
            import com.intellij.openapi.application.runReadActionBlocking

            class Refresher {
                fun refresh() {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        collect()
                    }
                }

                private fun collect(): Int = runReadActionBlocking { 1 }
            }
            """.trimIndent(),
        )
        val hit = freezeWarnings().singleOrNull()
        assertNotNull(hit)
        assertTrue(hit!!.description!!.contains("via Refresher.collect()"))
        assertTrue(hit.description!!.contains("Avoid usage in background threads"))
    }

    fun `test a RequiresBackgroundThread function calling ReadAction runBlocking is flagged`() {
        myFixture.configureByText(
            "Worker.kt",
            """
            import com.intellij.openapi.application.ReadAction
            import com.intellij.util.concurrency.annotations.RequiresBackgroundThread

            @RequiresBackgroundThread
            fun collect() {
                ReadAction.runBlocking<Throwable> { }
            }
            """.trimIndent(),
        )
        assertTrue(freezeWarnings().any { it.description!!.contains("@RequiresBackgroundThread function") })
    }

    fun `test reads inside invokeLater and nonBlocking are not flagged`() {
        myFixture.configureByText(
            "Ui.kt",
            """
            import com.intellij.openapi.application.ApplicationManager
            import com.intellij.openapi.application.ReadAction
            import com.intellij.openapi.application.runReadAction

            fun update() {
                ApplicationManager.getApplication().executeOnPooledThread {
                    ApplicationManager.getApplication().invokeLater { runReadAction { 1 } }
                    ReadAction.nonBlocking<Int> { runReadAction { 2 } }.executeSynchronously()
                }
            }
            """.trimIndent(),
        )
        assertTrue(freezeWarnings().isEmpty())
    }

    fun `test a project function that is also named runReadAction is not flagged`() {
        myFixture.configureByText(
            "Own.kt",
            """
            import com.intellij.openapi.application.ApplicationManager

            fun <T> runReadAction(block: () -> T): T = block()

            fun update() {
                ApplicationManager.getApplication().executeOnPooledThread {
                    runReadAction { 1 }
                }
            }
            """.trimIndent(),
        )
        assertTrue(freezeWarnings().isEmpty())
    }

    fun `test a read in a method that also calls checkCanceled is a weaker warning`() {
        myFixture.configureByText(
            "Careful.kt",
            """
            import com.intellij.openapi.application.ReadAction
            import com.intellij.openapi.progress.ProgressIndicator
            import com.intellij.openapi.progress.ProgressManager
            import com.intellij.openapi.progress.Task
            import com.intellij.openapi.project.Project

            class Careful(project: Project) : Task.Backgroundable(project, "Careful") {
                override fun run(indicator: ProgressIndicator) {
                    ProgressManager.checkCanceled()
                    ReadAction.run<Throwable> { }
                }
            }
            """.trimIndent(),
        )
        assertTrue(freezeWarnings(HighlightSeverity.WEAK_WARNING).any { it.description!!.contains("checkCanceled() call was found") })
    }

    fun `test a read with no background entry point is not flagged`() {
        myFixture.configureByText(
            "Plain.kt",
            """
            import com.intellij.openapi.application.ReadAction

            fun onEdt(): Int = ReadAction.compute<Int, Throwable> { 1 }
            """.trimIndent(),
        )
        assertTrue(freezeWarnings().isEmpty())
    }
}
