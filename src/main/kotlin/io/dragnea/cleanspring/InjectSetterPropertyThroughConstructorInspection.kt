package io.dragnea.cleanspring

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
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
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiThisExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlTag
import com.intellij.spring.model.properties.PropertyReference
import com.intellij.util.castSafelyTo
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

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

                fieldIsSetterOf.propagate(setterMethod.containingClass!!)

                query.processConstructorUsages(fieldIsSetterOf)

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

private fun PsiMethod.isCandidate(): Boolean {
    if (hasAnnotation("org.springframework.beans.factory.annotation.Autowired") && allUsagesAreRightAfterConstructorCall()) {
        return true
    }

    if (ReferencesSearch.search(this).firstIsInstanceOrNull<PropertyReference>() != null) {
        return true
    }

    return false
}

private fun PsiMethod.allUsagesAreRightAfterConstructorCall(): Boolean {
    return ReferencesSearch.search(this).map {
        val referenceExpression = it.element.castSafelyTo<PsiReferenceExpression>() ?: return@map false

        val qualifierExpression =
            referenceExpression.qualifierExpression.castSafelyTo<PsiReferenceExpression>() ?: return@map false

        val psiVariable = qualifierExpression.resolve().castSafelyTo<PsiVariable>() ?: return@map false

        val statements = ReferencesSearch.search(psiVariable).mapNotNull {
            it.element.parentOfType<PsiStatement>()
        }

        val block = psiVariable.parentOfType<PsiCodeBlock>() ?: return@map false

        val setterStatement = referenceExpression.parentOfType<PsiStatement>() ?: return@map false

        val firstReferencedStatement = block.statements.firstOrNull { it in statements } ?: return@map false

        firstReferencedStatement == setterStatement
    }.all { it }
}

private fun PsiMethod.propagateParameterToSuperCallAndConstructorUsages(field: PsiField) {
    val query = ReferencesSearch.search(this).findAll()

    addParameter(field)

    val superCall = body!!
        .statements[0]
        .cast<PsiExpressionStatement>()
        .expression
        .cast<PsiMethodCallExpression>()

    superCall.argumentList.add(field.factory.createExpressionFromText(field.name, this))

    query.processConstructorUsages(field)
}

private fun Collection<PsiReference>.processConstructorUsages(field: PsiField) {
    forEach {
        val reference = it.castSafelyTo<PsiJavaCodeReferenceElement>() ?: return@forEach

        when (val element = reference.element) {
            is PsiClass -> {
                element
                    .getNormalizedConstructor()
                    .propagateParameterToSuperCallAndConstructorUsages(field)
            }
            is PsiMethod -> {
                element
                    .containingClass!!
                    .getNormalizedConstructor()
                    .propagateParameterToSuperCallAndConstructorUsages(field)
            }
            else -> {
                when (val parent = reference.parent) {
                    is PsiNewExpression -> parent.replaceFirstSetterWithConstructorCall(field)
                    is PsiMethodCallExpression -> {
                        if (reference.text == "super") {
                            reference
                                .parentOfType<PsiMethod>()!!
                                .propagateParameterToSuperCallAndConstructorUsages(field)
                        }
                    }
                }
            }
        }
    }
}

private fun PsiField.propagate(setterClass: PsiClass) {
    val fieldClass = containingClass!!

    var currentClass = setterClass

    while (true) {
        val normalizedConstructor = currentClass.getNormalizedConstructor()

        if (currentClass == fieldClass) {
            normalizedConstructor.injectField(this)
            break
        }

        normalizedConstructor.propagateField(this)

        currentClass = currentClass.superClass!!
    }
}

private fun PsiNewExpression.replaceFirstSetterWithConstructorCall(field: PsiField) {
    val argumentList = argumentList!!

    val psiVariable = getPsiVariable()

    if (psiVariable == null) {
        getBeanAnnotatedMethod()?.addParameter(field)

        argumentList.add(factory.createExpressionFromText(field.name, this))
        return
    }

    val setterArgument = psiVariable.getSetterArgument(field)

    if (setterArgument == null) {
        argumentList.add(factory.createExpressionFromText("null", this))
        return
    }

    argumentList.add(setterArgument)

    val setterStatement = setterArgument.parentOfType<PsiStatement>()!!

    val parentOfType = parentOfType<PsiStatement>()!!
    val createExpressionFromText =
        factory.createStatementFromText(parentOfType.text, parentOfType.parentOfType<PsiBlockStatement>())

    setterStatement.replace(createExpressionFromText)

    parentOfType.delete()
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
            val psiReferenceExpression = parent.lExpression.castSafelyTo<PsiReferenceExpression>() ?: return null
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

private fun PsiMethod.injectField(psiField: PsiField) {
    addParameter(psiField)

    val name = psiField.name
    body?.add(factory.createStatementFromText("this.$name = $name;", this))
}

private fun PsiMethod.addParameter(psiField: PsiField) {
    findSuperMethods().forEach {
        it.addParameter(psiField)
    }

    parameterList.add(factory.createParameter(psiField.name, psiField.type))
}

private fun PsiMethod.propagateField(psiField: PsiField) {
    addParameter(psiField)

    val superCall = body!!.statements[0].cast<PsiMethodCallExpression>()
    superCall.argumentList.add(factory.createExpressionFromText(psiField.name, this))
}

private fun PsiMethod.fieldIsSetterOf(): PsiField? {
    if (!name.matches(Regex("set\\S+"))) return null

    val parameters = parameterList.parameters

    if (parameters.size != 1) return null

    if (!returnsVoid()) return null

    val statements = body?.statements ?: return null

    if (statements.size != 1) return null

    val expressionStatement = statements[0].castSafelyTo<PsiExpressionStatement>() ?: return null

    val assignmentExpression = expressionStatement.expression.castSafelyTo<PsiAssignmentExpression>() ?: return null

    val lExpression = assignmentExpression.lExpression.castSafelyTo<PsiReferenceExpression>() ?: return null

    lExpression.qualifierExpression.castSafelyTo<PsiThisExpression>() ?: return null

    val rExpression = assignmentExpression.rExpression.castSafelyTo<PsiReferenceExpression>() ?: return null

    if (rExpression.resolve() != parameters[0]) return null

    val psiField = lExpression.resolve().castSafelyTo<PsiField>() ?: return null

    if (psiField.containingClass != containingClass) return null

    return psiField
}

private fun PsiMethod.returnsVoid() = returnType == PsiType.VOID
