package io.dragnea.cleanspring

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.searches.ReferencesSearch

class PossiblyUnnecessaryBeanMethodInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                if (method.isBeanMethodWithOneExplicitUsage()) {
                    holder.registerProblem(
                        method.nameIdentifier!!,
                        "@Bean method can be made regular or inlined",
                        ProblemHighlightType.WARNING,
                        UnnecessaryBeanMethodInspection.Fix()
                    )
                }
            }
        }
    }
}

private fun PsiMethod.checkAnnotations(): Boolean {
    return hasAnnotation(BEAN_ANNOTATION)
}

private fun PsiMethod.checkUsages(): Boolean {
    val all = ReferencesSearch
        .search(this)
        .findAll()

    all.size == 1 || return false

    all.forEach { if (it.element.parent !is PsiMethodCallExpression) return false }

    return true
}

private fun PsiMethod.isBeanMethodWithOneExplicitUsage(): Boolean {
    checkAnnotations() || return false

    checkUsages() || return false

    return true
}
