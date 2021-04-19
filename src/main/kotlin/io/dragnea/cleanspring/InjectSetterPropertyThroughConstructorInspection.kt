package io.dragnea.cleanspring

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiThisExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlTag
import com.intellij.spring.model.properties.PropertyReference
import com.intellij.util.castSafelyTo
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.utils.addToStdlib.cast

class InjectSetterPropertyThroughConstructorInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                method.fieldIsSetterOf() ?: return

                val containingClass = method.containingClass ?: return

                if (containingClass.constructors.size > 1) return

                if (!method.isCandidate()) return

                holder.registerProblem(
                    method.nameIdentifier!!,
                    "Property can be injected through constructor",
                    ProblemHighlightType.WARNING,
                    Fix()
                )
            }
        }
    }

    class Fix : LocalQuickFix {
        override fun getFamilyName() = "Inject setter property through constructor"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val setterMethod = descriptor.psiElement.parentOfType<PsiMethod>()!!

            val fieldIsSetterOf = setterMethod.fieldIsSetterOf()!!

            val fieldClass = fieldIsSetterOf.containingClass!!

            runWriteAction {
                val normalizedConstructor = fieldClass.getNormalizedConstructor()

                val query = ReferencesSearch.search(normalizedConstructor).findAll()

                PropertyInjectionContext(
                    fieldIsSetterOf,
                    setterMethod.getAnnotation("org.springframework.beans.factory.annotation.Qualifier"),
                    setterMethod.parameterList.parameters[0].getAnnotation("org.springframework.beans.factory.annotation.Value")
                ).apply {
                    propagate(setterMethod.containingClass!!, fieldClass)
                    query.processConstructorUsages()
                }

                ReferencesSearch
                    .search(setterMethod)
                    .forEach {
                        when (it) {
                            is PropertyReference -> it.processXmlPropertyReference()
                        }
                    }

                setterMethod.delete()
            }
        }

        override fun startInWriteAction() = false
    }
}

private data class PropertyInjectionContext(
    val field: PsiField,
    val qualifierAnnotation: PsiAnnotation?,
    val valueAnnotation: PsiAnnotation?
) {
    private fun PsiMethod.propagateParameterToSuperCallAndConstructorUsages() {
        val query = ReferencesSearch.search(this).findAll()

        addParameter()

        val superCall = body!!
            .statements[0]
            .cast<PsiExpressionStatement>()
            .expression
            .cast<PsiMethodCallExpression>()

        superCall.argumentList.add(field.factory.createExpressionFromText(field.name, this))

        query.processConstructorUsages()
    }

    fun Collection<PsiReference>.processConstructorUsages() {
        forEach {
            val reference = it.castSafelyTo<PsiJavaCodeReferenceElement>() ?: return@forEach

            when (val element = reference.element) {
                is PsiClass -> {
                    element
                        .getNormalizedConstructor()
                        .propagateParameterToSuperCallAndConstructorUsages()
                }
                is PsiMethod -> {
                    element
                        .containingClass!!
                        .getNormalizedConstructor()
                        .propagateParameterToSuperCallAndConstructorUsages()
                }
                else -> {
                    when (val parent = reference.parent) {
                        is PsiNewExpression -> parent.processConstructorCall()
                        is PsiMethodCallExpression -> {
                            if (reference.text == "super") {
                                reference
                                    .parentOfType<PsiMethod>()!!
                                    .propagateParameterToSuperCallAndConstructorUsages()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun PsiNewExpression.processConstructorCall() {
        val argumentList = argumentList!!

        val psiVariable = getPsiVariable()

        if (psiVariable == null) {
            val beanAnnotatedMethod = getBeanAnnotatedMethod()

            if (beanAnnotatedMethod == null) {
                argumentList.add(factory.createExpressionFromText(field.type.defaultValue, this))
                return
            }

            beanAnnotatedMethod.addParameter()
            argumentList.add(factory.createExpressionFromText(field.name, this))
            return
        }

        val setterArgument = psiVariable.getSetterArgument(field)

        if (setterArgument == null) {
            argumentList.add(factory.createExpressionFromText(field.type.defaultValue, this))
            return
        }

        argumentList.add(setterArgument)

        val setterStatement = setterArgument.parentOfType<PsiStatement>()!!

        val constructorStatement = parentOfType<PsiStatement>()!!

        setterStatement.replace(
            factory.createStatementFromText(
                constructorStatement.text,
                constructorStatement.parentOfType<PsiBlockStatement>()
            )
        )

        constructorStatement.delete()
    }

    private fun PsiMethod.addParameter() {
        findSuperMethods().forEach {
            it.addParameter()
        }

        val parameter = parameterList
            .add(factory.createParameter(field.name, field.type))
            .cast<PsiParameter>()

        val modifierList = parameter.modifierList!!

        if (qualifierAnnotation != null) {
            modifierList.add(qualifierAnnotation)
        }

        if (valueAnnotation != null) {
            modifierList.add(valueAnnotation)
        }
    }

    private fun PsiMethod.injectField() {
        addParameter()

        val name = field.name
        body?.add(factory.createStatementFromText("this.$name = $name;", this))
    }

    private fun PsiMethod.propagateField() {
        addParameter()

        val superCall = body!!.statements[0].cast<PsiMethodCallExpression>()
        superCall.argumentList.add(factory.createExpressionFromText(field.name, this))
    }

    fun propagate(setterClass: PsiClass, fieldContainingClass: PsiClass) {
        var currentClass = setterClass

        while (true) {
            val normalizedConstructor = currentClass.getNormalizedConstructor()

            if (currentClass == fieldContainingClass) {
                normalizedConstructor.injectField()
                break
            }

            normalizedConstructor.propagateField()

            currentClass = currentClass.superClass!!
        }
    }
}

private fun PsiMethod.isCandidate(): Boolean {
    if (hasAnnotation("org.springframework.beans.factory.annotation.Autowired") && allUsagesAreRightAfterConstructorCall()) {
        return true
    }

    return false
}

private fun PsiMethod.allUsagesAreRightAfterConstructorCall(): Boolean {
    return ReferencesSearch.search(this).map {
        it !is PropertyReference || return@map true

        val referenceExpression =
            it.element.castSafelyTo<PsiReferenceExpression>() ?: return@map false

        val qualifierExpression =
            referenceExpression.qualifierExpression.castSafelyTo<PsiReferenceExpression>()
                ?: return@map false

        val psiVariable =
            qualifierExpression.resolve().castSafelyTo<PsiVariable>() ?: return@map false

        val block = psiVariable.parentOfType<PsiCodeBlock>() ?: return@map false

        val statements = ReferencesSearch.search(psiVariable, LocalSearchScope(block)).mapNotNull {
            it.element.parentOfType<PsiStatement>()
        }

        val setterStatement = referenceExpression.parentOfType<PsiStatement>() ?: return@map false

        val firstReferencedStatement =
            block.statements.firstOrNull { it in statements } ?: return@map false

        firstReferencedStatement == setterStatement
    }.all { it }
}

private fun PsiNewExpression.getBeanAnnotatedMethod(): PsiMethod? {
    val psiMethod = parentOfType<PsiMethod>() ?: return null

    if (!psiMethod.hasAnnotation("org.springframework.context.annotation.Bean")) return null

    return psiMethod
}

private fun PsiVariable.getSetterArgument(field: PsiField): PsiExpression? {
    val setterArguments = ReferencesSearch
        .search(this)
        .mapNotNull { it.getSetterArgumentExpression(field) }

    if (setterArguments.size != 1) return null

    return setterArguments[0]
}

private fun PsiReference.getSetterArgumentExpression(field: PsiField): PsiExpression? {
    if (this !is PsiReferenceExpression) return null

    val methodExpression = parent

    val methodCallExpression = methodExpression.parent

    if (methodCallExpression !is PsiMethodCallExpression) return null

    if (methodCallExpression.methodExpression != methodExpression) return null

    val method = methodCallExpression.resolveMethod() ?: return null

    if (method.fieldIsSetterOf() != field) return null

    return methodCallExpression.argumentList.expressions[0]
}

private fun PsiNewExpression.getPsiVariable(): PsiVariable? {
    return when (val parent = parent) {
        is PsiVariable -> {
            if (parent.initializer != this) return null
            parent
        }
        is PsiAssignmentExpression -> {
            val psiReferenceExpression =
                parent.lExpression.castSafelyTo<PsiReferenceExpression>() ?: return null
            psiReferenceExpression.resolve().cast()
        }
        else -> null
    }
}

private fun PropertyReference.processXmlPropertyReference() {
    val propertyTag = element.parentOfType<XmlTag>()!!
    propertyTag.name = "constructor-arg"
}

private val PsiElement.factory get() = PsiElementFactory.getInstance(project)

private fun PsiClass.getNormalizedConstructor(): PsiMethod {
    val constructor = getOrCreateConstructor()

    val body = constructor.body ?: return constructor

    val statements = body.statements

    val superCall = factory.createStatementFromText("super();", this)

    if (statements.isEmpty()) {
        body.add(superCall)
        return constructor
    }

    val firstStatement = statements[0]
    if (!firstStatement.isSuperCall()) {
        body.addBefore(superCall, firstStatement)
    }

    return constructor
}

private fun PsiStatement.isSuperCall(): Boolean {
    if (this !is PsiExpressionStatement) return false

    val expression = expression

    if (expression !is PsiMethodCallExpression) return false

    return expression.methodExpression.referenceName == PsiKeyword.SUPER
}

private fun PsiClass.getOrCreateConstructor(): PsiMethod = if (constructors.isEmpty()) {
    addDefaultConstructor()
} else {
    constructors[0]
}

private fun PsiClass.addDefaultConstructor(): PsiMethod {
    val defaultConstructor = factory.createMethodFromText(
        "public $name() {\nsuper();\n}",
        this
    )

    val firstOrNull = methods.firstOrNull()

    val psiElement = if (firstOrNull == null) {
        add(defaultConstructor)
    } else {
        addBefore(defaultConstructor, firstOrNull)
    }

    return psiElement.cast()
}

private fun PsiMethod.fieldIsSetterOf(): PsiField? {
    if (!name.matches(Regex("set\\S+"))) return null

    val parameters = parameterList.parameters

    if (parameters.size != 1) return null

    if (!returnsVoid()) return null

    val statements = body?.statements ?: return null

    if (statements.size != 1) return null

    val expressionStatement = statements[0].castSafelyTo<PsiExpressionStatement>() ?: return null

    val assignmentExpression =
        expressionStatement.expression.castSafelyTo<PsiAssignmentExpression>() ?: return null

    val lExpression =
        assignmentExpression.lExpression.castSafelyTo<PsiReferenceExpression>() ?: return null

    lExpression.qualifierExpression.castSafelyTo<PsiThisExpression>() ?: return null

    val rExpression =
        assignmentExpression.rExpression.castSafelyTo<PsiReferenceExpression>() ?: return null

    if (rExpression.resolve() != parameters[0]) return null

    val psiField = lExpression.resolve().castSafelyTo<PsiField>() ?: return null

    if (psiField.containingClass != containingClass) return null

    return psiField
}

private fun PsiMethod.returnsVoid() = returnType == PsiType.VOID

private val PsiType.defaultValue
    get() = when {
        this == PsiType.BYTE -> "0"
        this == PsiType.CHAR -> "0"
        this == PsiType.DOUBLE -> "0"
        this == PsiType.FLOAT -> "0"
        this == PsiType.INT -> "0"
        this == PsiType.LONG -> "0"
        this == PsiType.SHORT -> "0"
        this == PsiType.BOOLEAN -> "false"
        else -> "null"
    }
