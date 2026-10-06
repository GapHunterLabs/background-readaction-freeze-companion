package dev.gaphunter.backgroundreadactionfreezecompanion.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.psi.PsiFile
import dev.gaphunter.backgroundreadactionfreezecompanion.detect.KotlinBackgroundReadActionFinder

/** Kotlin counterpart of [BackgroundReadActionFreezeInspection] -- see [KotlinBackgroundReadActionFinder]. */
class KotlinBackgroundReadActionFreezeInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file.text.length > FreezeProblems.MAX_FILE_LENGTH) return null
        return FreezeProblems.toProblems(file, KotlinBackgroundReadActionFinder.findAll(file), manager, isOnTheFly)
    }
}
