package io.dragnea.cleanspring

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnonymousClass
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
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiThisExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlTag
import com.intellij.spring.constants.SpringAnnotationsConstants
import com.intellij.spring.constants.SpringAnnotationsConstants.AUTOWIRED
import com.intellij.spring.constants.SpringAnnotationsConstants.QUALIFIER
import com.intellij.spring.constants.SpringAnnotationsConstants.VALUE
import com.intellij.spring.model.properties.PropertyReference
import com.intellij.util.Query
import com.intellij.util.castSafelyTo
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.j2k.getContainingClass
import org.jetbrains.kotlin.j2k.getContainingMethod
import org.jetbrains.kotlin.utils.addToStdlib.cast

class InjectPropertyThroughConstructorInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitField(field: PsiField) {
                field.isCandidate() || return

                holder.registerProblem(
                    field.nameIdentifier,
                    "Field property can be injected through constructor",
                    ProblemHighlightType.WARNING,
                    FieldFix()
                )
            }

            override fun visitMethod(method: PsiMethod) {
                method.isCandidate() || return

                holder.registerProblem(
                    method.nameIdentifier!!,
                    "Setter property can be injected through constructor",
                    ProblemHighlightType.WARNING,
                    MethodFix()
                )
            }
        }
    }

    class FieldFix : LocalQuickFix {
        override fun getFamilyName() = "Inject field property through constructor"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val field = descriptor.psiElement.parentOfType<PsiField>()!!

            runWriteAction {
                PropertyInjectionContext(field).apply {
                    field.containingClass!!.getOrCreateConstructor()
                        .processConstructorUsagesForField {
                            body?.add(
                                factory.createStatementFromText(
                                    "this.${field.name} = ${field.name};", body
                                )
                            )
                        }
                }

                field.getAnnotation(AUTOWIRED)?.delete()
                field.getAnnotation(VALUE)?.delete()
                field.getAnnotation(QUALIFIER)?.delete()

                field.initializer?.delete()
                field.modifierList!!.setModifierProperty(PsiModifier.FINAL, true)
            }
        }

        override fun startInWriteAction() = false
    }

    class MethodFix : LocalQuickFix {
        override fun getFamilyName() = "Inject setter property through constructor"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val setterMethod = descriptor.psiElement.parentOfType<PsiMethod>()!!

            val setterParameter = setterMethod.setterParameter()!!

            val setterClass = setterParameter.getContainingClass()!!

            runWriteAction {
                PropertyInjectionContext(setterParameter).apply {
                    setterClass.getOrCreateConstructor().processConstructorUsagesForSetter {
                        body?.add(setterParameter.getContainingMethod()!!.body!!.statements[0])
                    }
                }

                setterMethod
                    .searchReferencesInProject()
                    .forEach {
                        when (it) {
                            is PropertyReference -> it.processXmlPropertyReference()
                        }
                    }

                setterMethod.delete()
                setterMethod.getField().modifierList!!.setModifierProperty(PsiModifier.FINAL, true)
            }
        }

        override fun startInWriteAction() = false
    }
}

private data class PropertyInjectionContext(
    val property: PsiVariable
) {
    fun PsiMethod.processConstructorUsagesForSetter(bodyTransformer: PsiMethod.() -> Unit) =
        processConstructorUsages(bodyTransformer) { processConstructorCall() }

    fun PsiMethod.processConstructorUsagesForField(bodyTransformer: PsiMethod.() -> Unit) =
        processConstructorUsages(bodyTransformer) { processBeanMethodOrDefault() }

    private fun PsiMethod.processConstructorUsages(bodyTransformer: PsiMethod.() -> Unit,
                                                   newExpressionProcessor: PsiNewExpression.() -> Unit) {
        val query = this.searchReferencesInProject().findAll()

        addParameter()

        bodyTransformer()

        query.forEach {
            val reference = it.castSafelyTo<PsiJavaCodeReferenceElement>() ?: return@forEach

            when (val element = reference.element) {
                is PsiClass -> {
                    element
                        .getOrCreateConstructor()
                        .propagateParameterToSuperCallAndConstructorUsages()
                }
                is PsiMethod -> {
                    element
                        .containingClass!!
                        .getOrCreateConstructor()
                        .propagateParameterToSuperCallAndConstructorUsages()
                }
                else -> {
                    when (val parent = reference.parent) {
                        is PsiNewExpression -> parent.newExpressionProcessor()
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

    private fun PsiMethod.propagateParameterToSuperCallAndConstructorUsages() =
        processConstructorUsagesForSetter {
            getSuperCall().argumentList.add(
                property.factory.createExpressionFromText(property.name!!, this)
            )
        }

    private fun PsiNewExpression.processConstructorCall() {
        val psiVariable = getPsiVariable()

        if (psiVariable == null) {
            processBeanMethodOrDefault()
            return
        }

        val setterArgument = psiVariable.getSetterArgument()

        if (setterArgument == null) {
            processBeanMethodOrDefault()
            return
        }

        argumentList!!.add(setterArgument)

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

    private fun PsiNewExpression.processBeanMethodOrDefault() {
        val beanAnnotatedMethod = getBeanAnnotatedMethod()
        val argumentList = argumentList!!

        if (beanAnnotatedMethod == null) {
            argumentList.add(
                factory.createExpressionFromText(
                    property.type.defaultValue,
                    this
                )
            )
            return
        }

        val name = property.name!!
        if (beanAnnotatedMethod.parameterList.parameters.none { it.name == name }) {
            beanAnnotatedMethod.addParameter()
        }
        argumentList.add(factory.createExpressionFromText(name, this))
    }

    private fun PsiVariable.getSetterArgument(): PsiExpression? {
        val setterArguments = this
            .searchReferencesInProject()
            .mapNotNull { it.getSetterArgumentExpression() }

        if (setterArguments.size != 1) return null

        return setterArguments[0]
    }

    private fun PsiReference.getSetterArgumentExpression(): PsiExpression? {
        if (this !is PsiReferenceExpression) return null

        val methodExpression = parent

        val methodCallExpression = methodExpression.parent

        if (methodCallExpression !is PsiMethodCallExpression) return null

        if (methodCallExpression.methodExpression != methodExpression) return null

        val method = methodCallExpression.resolveMethod() ?: return null

        if (method.setterParameter() != property) return null

        return methodCallExpression.argumentList.expressions[0]
    }

    private fun PsiMethod.addParameter() {
        findSuperMethods().forEach {
            it.addParameter()
        }

        val parameter = parameterList
            .add(factory.createParameter(property.name!!, property.type))
            .cast<PsiParameter>()

        val modifierList = parameter.modifierList!!

        property
            .getContainingMethod()
            ?.getAnnotation(QUALIFIER)
            ?.let { modifierList.add(it) }

        property
            .getAnnotation(QUALIFIER)
            ?.let { modifierList.add(it) }

        property
            .getAnnotation(VALUE)
            ?.let { modifierList.add(it) }
    }
}

private fun PsiMethod.isCandidate(): Boolean {
    setterParameter() ?: return false

    val containingClass = containingClass ?: return false

    containingClass.constructors.size <= 1 || return false

    hasAnnotation(AUTOWIRED) || return false

    allUsagesAreRightAfterConstructorCall() || return false

    getField().getSoleAssignment() ?: return false

    return true
}

private fun PsiField.isCandidate(): Boolean {
    hasAnnotation(AUTOWIRED) || hasAnnotation(VALUE) || return false

    assignmentExpressions().isEmpty() || return false

    val containingClass = containingClass ?: return false

    containingClass !is PsiAnonymousClass || return false

    !containingClass.isTestNgSpringTestContextClass() || return false

    !hasFieldWithSameNameInParentClass() || return false

    !hasFieldWithSameNameInAnySubclass() || return false

    when (containingClass.constructors.size) {
        0 -> containingClass.noUsageIsFromCalledBeanMethods() || return false
        1 -> {
            val constructor = containingClass.constructors[0]

            val parameters = constructor.parameterList.parameters
            if (parameters.isNotEmpty()) {
                !parameters.last().isVarArgs || return false
            }

            constructor.noUsageIsFromCalledBeanMethods() || return false
        }
        else -> return false
    }

    return true
}

private fun PsiField.hasFieldWithSameNameInAnySubclass(): Boolean = containingClass!!
    .searchReferencesInProject()
    .map { hasFieldWithSameNameInSubclass(it) }
    .any { it }

private fun PsiField.hasFieldWithSameNameInSubclass(reference: PsiReference): Boolean {
    val subclass = reference.getSubclass() ?: return false

    val name = name
    return subclass.fields.any { it.name == name }
}

private fun PsiReference.getSubclass(): PsiClass? {
    this is PsiJavaCodeReferenceElement || return null

    val psiClass = element.parentOfType<PsiClass>() ?: return null

    val extendsList = psiClass.extendsList ?: return null

    this in extendsList.referenceElements || return null

    return psiClass
}

private fun PsiField.hasFieldWithSameNameInParentClass(): Boolean {
    val name = name
    var currentClass = containingClass!!.superClass

    while (true) {
        if (currentClass == null) return false

        currentClass.fields.firstOrNull { it.name == name } == null || return true

        currentClass = currentClass.superClass
    }
}

private fun PsiClass.isTestNgSpringTestContextClass(): Boolean {
    // TODO: TestNG part

    var currentClass: PsiClass? = this

    while (true) {
        if (currentClass == null) return false

        currentClass.hasAnnotation(SpringAnnotationsConstants.CONTEXT_CONFIGURATION) && return true

        currentClass = currentClass.superClass
    }
}

private fun PsiMethod.allUsagesAreRightAfterConstructorCall(): Boolean = this
    .searchReferencesInProject()
    .map { it.isSetterCallRightAfterConstructorCall() }
    .all { it }

private fun PsiElement.noUsageIsFromCalledBeanMethods(): Boolean = this
    .searchReferencesInProject()
    .map { it.isNewExpressionInsideCalledBeanMethod() }
    .none { it }

private fun PsiReference.isNewExpressionInsideCalledBeanMethod(): Boolean {
    val reference = castSafelyTo<PsiJavaCodeReferenceElement>() ?: return false

    val newExpression = reference.element.parent.castSafelyTo<PsiNewExpression>() ?: return false

    val method = newExpression.parentOfType<PsiMethod>() ?: return false

    return method.isCalledBeanMethod()
}

private fun PsiMethod.isBeanMethod() = hasAnnotation(SpringAnnotationsConstants.JAVA_SPRING_BEAN)

private fun PsiMethod.isCalledBeanMethod(): Boolean {
    isBeanMethod() || return false

    return this
        .searchReferencesInProject()
        .map { it.isMethodCall() }
        .any { it }
}

private fun PsiReference.isMethodCall(): Boolean {
    val reference = castSafelyTo<PsiJavaCodeReferenceElement>() ?: return false

    val methodCall =
        reference.element.parentOfType<PsiMethodCallExpression>() ?: return false

    methodCall.parentOfType<PsiMethod>() ?: return false

    return true
}

fun PsiReference.isSetterCallRightAfterConstructorCall(): Boolean {
    this !is PropertyReference || return true

    val referenceExpression =
        element.castSafelyTo<PsiReferenceExpression>() ?: return false

    val qualifierExpression =
        referenceExpression.qualifierExpression.castSafelyTo<PsiReferenceExpression>()
            ?: return false

    val psiVariable =
        qualifierExpression.resolve().castSafelyTo<PsiVariable>() ?: return false

    if (psiVariable is PsiLocalVariable) {
        val block = psiVariable.parentOfType<PsiCodeBlock>() ?: return false

        val statements =
            ReferencesSearch.search(psiVariable, LocalSearchScope(block)).mapNotNull {
                it.element.parentOfType<PsiStatement>()
            }

        val setterStatement =
            referenceExpression.parentOfType<PsiStatement>() ?: return false

        val firstReferencedStatement =
            block.statements.firstOrNull { it in statements } ?: return false

        return firstReferencedStatement == setterStatement
    }

    if (psiVariable is PsiField) {
        val soleAssignment = psiVariable.getSoleAssignment() ?: return false

        val assignmentBlock = soleAssignment.parentOfType<PsiCodeBlock>() ?: return false

        val setterCallBlock = referenceExpression.parentOfType<PsiCodeBlock>() ?: return false

        assignmentBlock == setterCallBlock || return false

        val soleAssignmentStatement = soleAssignment.parentOfType<PsiStatement>()

        val statements =
            ReferencesSearch
                .search(psiVariable, LocalSearchScope(setterCallBlock))
                .mapNotNull { it.element.parentOfType<PsiStatement>() }
                .filter { it != soleAssignmentStatement }

        val setterStatement =
            referenceExpression.parentOfType<PsiStatement>() ?: return false

        val firstReferencedStatement =
            setterCallBlock.statements.firstOrNull { it in statements } ?: return false

        return firstReferencedStatement == setterStatement
    }

    return false
}

private fun PsiNewExpression.getBeanAnnotatedMethod(): PsiMethod? {
    val psiMethod = parentOfType<PsiMethod>() ?: return null

    if (!psiMethod.isBeanMethod()) return null

    return psiMethod
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

private fun PsiMethod.getSuperCall(): PsiMethodCallExpression {
    val body = body!!

    val statements = body.statements
    val superCall = factory.createStatementFromText("super();", this)

    if (statements.isEmpty()) {
        return body
            .add(superCall)
            .cast<PsiExpressionStatement>()
            .expression
            .cast()
    }

    val firstStatement = statements[0]

    return firstStatement.asSuperCall() ?: body
        .addBefore(superCall, firstStatement)
        .cast<PsiExpressionStatement>()
        .expression
        .cast()
}

private fun PsiStatement.asSuperCall(): PsiMethodCallExpression? {
    if (this !is PsiExpressionStatement) return null

    val expression = expression

    if (expression !is PsiMethodCallExpression) return null

    expression.methodExpression.referenceName == PsiKeyword.SUPER || return null

    return expression
}

private fun PsiClass.getOrCreateConstructor(): PsiMethod = if (constructors.isEmpty()) {
    addDefaultConstructor()
} else {
    constructors[0]
}

private fun PsiClass.addDefaultConstructor(): PsiMethod {
    val defaultConstructor = factory.createMethodFromText(
        "public $name() {}",
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

private fun PsiMethod.setterParameter(): PsiParameter? {
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

    return parameters[0]
}

private fun PsiMethod.getField() = body!!
    .statements[0]
    .cast<PsiExpressionStatement>()
    .expression
    .cast<PsiAssignmentExpression>()
    .lExpression
    .cast<PsiReferenceExpression>()
    .resolve()
    .cast<PsiField>()

private fun PsiField.getSoleAssignment(): PsiAssignmentExpression? {
    val assignments = assignmentExpressions()

    if (assignments.size != 1) return null

    return assignments[0]
}

private fun PsiField.assignmentExpressions() = this
    .searchReferencesInProject()
    .mapNotNull { it.asAssignmentExpression() }

private fun PsiReference.asAssignmentExpression(): PsiAssignmentExpression? {
    if (this !is PsiReferenceExpression) return null

    val parent = element.parent

    if (parent !is PsiAssignmentExpression) return null

    parent.lExpression == this || return null

    return parent
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

private fun PsiElement.searchReferencesInProject(): Query<PsiReference> =
    ReferencesSearch.search(this, GlobalSearchScope.projectScope(project))
