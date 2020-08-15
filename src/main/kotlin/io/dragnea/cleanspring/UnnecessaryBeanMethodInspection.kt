package io.dragnea.cleanspring

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import org.apache.commons.lang.StringUtils
import org.jetbrains.kotlin.utils.addToStdlib.cast

const val BEAN_ANNOTATION = "org.springframework.context.annotation.Bean"
const val IMPORT_ANNOTATION = "org.springframework.context.annotation.Import"

class UnnecessaryBeanMethodInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                if (method.isSimpleBeanMethod()) {
                    holder.registerProblem(
                        method.nameIdentifier!!,
                        "@Bean method can be replaced with @Import",
                        ProblemHighlightType.WARNING,
                        Fix()
                    )
                }
            }
        }
    }

    class Fix : LocalQuickFix {
        override fun getFamilyName() = "Replace @Bean method with @Import"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val beanMethod = descriptor.psiElement.parentOfType<PsiMethod>()!!

            val configurationClass = beanMethod.containingClass ?: return

            val psiClass = beanMethod.getClassToImport()

            configurationClass.importClass(psiClass)

            beanMethod.replaceUsagesWithParameters()

            beanMethod.delete()
        }

        override fun startInWriteAction() = true
    }
}

// TODO: Make this a separate inspection
private fun PsiMethod.replaceUsagesWithParameters() {
    ReferencesSearch
        .search(this)
        .findAll()
        .forEach { it.replaceWithParameter(this) }
}

private fun PsiReference.replaceWithParameter(method: PsiMethod) {
    val element = element.parentOfType<PsiMethodCallExpression>() ?: return

    val beanMethod = element.parentOfType<PsiMethod>() ?: return

    val returnType = method.returnType!!

    val factory = PsiElementFactory.getInstance(element.project)

    val parameterName = StringUtils.uncapitalize(returnType.cast<PsiClassType>().name)

    beanMethod.parameterList.add(factory.createParameter(parameterName, returnType))

    element.replace(factory.createExpressionFromText(parameterName, beanMethod))
}

private fun PsiClass.importClass(classToImport: PsiClass) {
    classToImport.makeAccessibleIfNecessary(this)

    val importAnnotation = getOrAddImportAnnotation()

    val elementFactory = PsiElementFactory.getInstance(project)

    when (val valueAttribute = importAnnotation.findAttributeValue("value")) {
        null -> {
            importAnnotation.setDeclaredAttributeValue(
                "value",
                elementFactory
                    .createExpressionFromText("${classToImport.getImportReferenceName()}.class", this)
                    .cast<PsiClassObjectAccessExpression>()
            )
        }
        is PsiArrayInitializerMemberValue -> {
            val arrayInitializerMemberValue = valueAttribute.cast<PsiArrayInitializerMemberValue>()

            arrayInitializerMemberValue.addBefore(
                elementFactory.createExpressionFromText("${classToImport.getImportReferenceName()}.class", this),
                arrayInitializerMemberValue.lastChild
            )
        }
        is PsiClassObjectAccessExpression -> {
            importAnnotation.replace(elementFactory.createAnnotationFromText(
                "@${importAnnotation.nameReferenceElement!!.text}({${valueAttribute.text}, ${classToImport.getImportReferenceName()}.class})",
                this
            ))
        }
        else -> throw IllegalStateException("Impossible")
    }
}

private fun PsiClass.makeAccessibleIfNecessary(configurationClass: PsiClass) {
    val modifierList = modifierList!!
    if (parent == configurationClass && modifierList.hasModifierProperty(PsiModifier.PRIVATE)) {
        modifierList.setModifierProperty(PsiModifier.PACKAGE_LOCAL, true)
    }
}

private fun PsiClass.getImportReferenceName(): String {
    val parent = parent
    val name = name!!

    if (parent is PsiClass) {
        return "${parent.name}.$name"
    }

    return name
}

private fun PsiClass.getOrAddImportAnnotation(): PsiAnnotation {
    val modifierList = modifierList!!

    return modifierList.findAnnotation(IMPORT_ANNOTATION)
        ?: modifierList.addAnnotation(IMPORT_ANNOTATION)
}

private fun PsiMethod.checkAnnotations(): Boolean {
    val annotations = annotations

    annotations.size == 1 || return false

    return annotations[0].qualifiedName == BEAN_ANNOTATION
}

private fun PsiMethod.isSimpleBeanMethod(): Boolean {
    checkAnnotations() || return false

    checkUsages() || return false

    val statements = this.body!!.statements

    when (statements.size) {
        1 -> {
            val statement = statements[0]

            if (statement !is PsiReturnStatement) {
                return false
            }

            val returnValue = statement.returnValue

            if (returnValue !is PsiNewExpression) {
                return false
            }

            return returnValue.isSimpleConstructorCall(parameterList)
        }
        2 -> {
            val localVariable = statements[0].getVariableDeclarationWithNewInitializer(parameterList) ?: return false

            val statement1 = statements[1]

            if (statement1 !is PsiReturnStatement) {
                return false
            }

            val returnValue = statement1.returnValue

            if (returnValue !is PsiReferenceExpression) {
                return false
            }

            returnValue.resolve() == localVariable || return false

            return true
        }
        else -> return false
    }
}

private fun PsiMethod.checkUsages(): Boolean {
    ReferencesSearch
        .search(this)
        .forEach { if (!it.checkUsage()) return false }

    return true
}

private fun PsiReference.checkUsage(): Boolean {
    val methodOfUsage = element.parentOfType<PsiMethod>() ?: return false

    // TODO: Possibly unnecessary bean inspection: single usage of the bean method inside another non-bean method
    methodOfUsage.hasAnnotation(BEAN_ANNOTATION) || return false

    return true
}

private fun PsiNewExpression.isSimpleConstructorCall(parameterList: PsiParameterList): Boolean {
    anonymousClass == null || return false

    val argumentList = argumentList ?: return false

    argumentList.expressionCount == parameterList.parametersCount || return false

    argumentList.expressions.forEach { it is PsiReferenceExpression || return false }

    return true
}

private fun PsiMethod.getClassToImport(): PsiClass {
    val statements = this.body!!.statements

    when (statements.size) {
        1 -> {
            return statements[0]
                .cast<PsiReturnStatement>()
                .returnValue
                .cast<PsiNewExpression>()
                .classReference!!
                .resolve()
                .cast()
        }
        2 -> {
            return statements[0]
                .cast<PsiDeclarationStatement>()
                .declaredElements[0]
                .cast<PsiLocalVariable>()
                .initializer
                .cast<PsiNewExpression>()
                .classReference!!
                .resolve()
                .cast()
        }
        else -> throw IllegalStateException("This is impossible")
    }
}

private fun PsiStatement.getVariableDeclarationWithNewInitializer(parameterList: PsiParameterList): PsiLocalVariable? {
    if (this !is PsiDeclarationStatement) {
        return null
    }

    val declaredElements = declaredElements

    declaredElements.size == 1 || return null

    val element = declaredElements[0]

    if (element !is PsiLocalVariable) {
        return null
    }

    val initializer = element.initializer

    if (initializer !is PsiNewExpression) {
        return null
    }

    initializer.isSimpleConstructorCall(parameterList) || return null

    return element
}
