/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.psi.KtDeclaration

fun KtDeclaration.expectedDeclarationIfAny(): KtDeclaration? = TODO()

fun KtDeclaration.isExpectDeclaration(): Boolean = TODO()

fun <T> Project.runReadActionInSmartMode(action: () -> T): T = TODO()