package io.dragnea.cleanspring

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.spring.constants.SpringAnnotationsConstants
import com.intellij.util.Query

val PsiElement.factory: PsiElementFactory get() = PsiElementFactory.getInstance(project)

fun PsiElement.references(): Query<PsiReference> = ReferencesSearch.search(this, useScope)

fun PsiMethod.isBeanMethod() = hasAnnotation(SpringAnnotationsConstants.JAVA_SPRING_BEAN)
