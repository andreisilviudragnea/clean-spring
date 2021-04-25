package io.dragnea.cleanspring

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.parentOfType
import com.intellij.spring.constants.SpringAnnotationsConstants.AUTOWIRED
import com.intellij.spring.constants.SpringAnnotationsConstants.QUALIFIER
import com.intellij.spring.constants.SpringAnnotationsConstants.VALUE
import com.intellij.util.castSafelyTo
import org.jetbrains.kotlin.utils.addToStdlib.cast

class InjectFieldAsBeanParameterInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                expression.canBeInjectedAsBeanParameter() || return

                holder.registerProblem(
                    expression.element,
                    "Property can be injected as @Bean parameter",
                    ProblemHighlightType.WARNING,
                    Fix()
                )
            }
        }
    }

    class Fix : LocalQuickFix {
        override fun getFamilyName() = "Inject property as @Bean parameter"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val expression = descriptor.psiElement.cast<PsiReferenceExpression>()

            // safe cast because previous fixes can invalidate new fixes,
            // when field is referenced more than once in the same method
            val field = expression.resolve().castSafelyTo<PsiField>() ?: return

            val beanMethod = expression.parentOfType<PsiMethod>()!!

            expression.qualifierExpression = null

            val parameter = beanMethod.parameterList.add(
                beanMethod.factory.createParameter(field.name, field.type)
            ).cast<PsiParameter>()

            val modifierList = parameter.modifierList!!

            field.getAnnotation(QUALIFIER)?.let { modifierList.add(it) }

            field.getAnnotation(VALUE)?.let { modifierList.add(it) }

            if (field.references().findAll().isEmpty()) {
                field.delete()
            }
        }
    }
}

private fun PsiReferenceExpression.canBeInjectedAsBeanParameter(): Boolean {
    val psiField = resolve().castSafelyTo<PsiField>() ?: return false

    psiField.hasAnnotation(AUTOWIRED) || psiField.hasAnnotation(VALUE) || return false

    val psiMethod = parentOfType<PsiMethod>() ?: return false

    psiMethod.isBeanMethod() || return false

    return true
}
