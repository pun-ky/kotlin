/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.asJava

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType

// Returns original declaration if given PsiElement is a Kotlin light element, and element itself otherwise
val PsiElement.unwrapped: PsiElement? get() = TODO()
val PsiElement.namedUnwrappedElement: PsiNamedElement? get() = TODO()
fun KtClassOrObject.toLightClassWithBuiltinMapping(): PsiClass? = TODO()
fun PsiElement.toLightMethods(): List<PsiMethod> = TODO()
fun KtClassOrObject.toLightClass(): KtLightClass? = TODO()
fun KtElement.toLightElements(): List<PsiNamedElement> = TODO()
fun createKtFakeLightClass(kotlinOrigin: KtClassOrObject): PsiClass = TODO()
fun PsiElement.getRepresentativeLightMethod(): PsiMethod? = TODO()
