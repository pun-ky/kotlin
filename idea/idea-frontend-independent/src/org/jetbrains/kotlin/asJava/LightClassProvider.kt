/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.asJava

import com.intellij.psi.*
import com.intellij.psi.impl.light.AbstractLightClass
import com.intellij.psi.impl.light.LightMethod
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.load.java.structure.LightClassOriginKind
import org.jetbrains.kotlin.psi.*

class KtFakeLightMethod private constructor(
    val ktDeclaration: KtNamedDeclaration,
    ktClassOrObject: KtClassOrObject, override val kotlinOrigin: KtNamedDeclaration?
) : LightMethod(), KtLightElement<KtNamedDeclaration, PsiMethod>

class KtFakeLightClass(override val kotlinOrigin: KtClassOrObject, override val originKind: LightClassOriginKind) :
    AbstractLightClass(kotlinOrigin.manager, KotlinLanguage.INSTANCE),
    KtLightClass


object LightClassUtil {
    fun getLightFieldForCompanionObject(companionObject: KtClassOrObject): PsiField? = TODO()
    fun getLightClassPropertyMethods(parameter: KtParameter): PropertyAccessorsPsiMethods = TODO()
    fun getLightClassPropertyMethods(property: KtProperty): PropertyAccessorsPsiMethods = TODO()
    fun getLightClassMethods(function: KtFunction): List<PsiMethod> = TODO()

    class PropertyAccessorsPsiMethods(val getter: PsiMethod?,
                                      val setter: PsiMethod?,
                                      val backingField: PsiField?,
                                      additionalAccessors: List<PsiMethod>) : Iterable<PsiMethod> {
        val allDeclarations: List<PsiNamedElement> get() = TODO()
    }
}

enum class ImpreciseResolveResult {
    MATCH,
    NO_MATCH,
    UNSURE;
}
