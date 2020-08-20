/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.util

import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeSubstitutor

class FuzzyType(
    val type: KotlinType,
    freeParameters: Collection<TypeParameterDescriptor>
) {
    fun checkIsSuperTypeOf(otherType: KotlinType): TypeSubstitutor? = TODO()

}