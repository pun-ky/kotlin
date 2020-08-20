/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.stubindex

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

@Suppress("EqualsOrHashCode") // DelegatingGlobalSearchScope requires to provide calcHashCode()
class KotlinSourceFilterScope {
    companion object {
        @JvmStatic
        fun sourcesAndLibraries(delegate: GlobalSearchScope, project: Project): GlobalSearchScope = TODO()
    }
}