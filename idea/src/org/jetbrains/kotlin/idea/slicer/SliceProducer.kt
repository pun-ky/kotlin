/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.slicer

import com.intellij.slicer.SliceUsage
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.idea.findUsages.handlers.SliceUsageProcessor

interface SliceProducer {
    fun produce(usage: UsageInfo, mode: KotlinSliceAnalysisMode, parent: SliceUsage): Collection<SliceUsage>?

    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int

    object Trivial : SliceProducer {
        override fun produce(usage: UsageInfo, mode: KotlinSliceAnalysisMode, parent: SliceUsage): Collection<SliceUsage>? {
            return null
        }

        override fun equals(other: Any?) = other === this
        override fun hashCode() = 0
    }
}

fun SliceProducer.produceAndProcess(
    sliceUsage: SliceUsage,
    mode: KotlinSliceAnalysisMode,
    parentUsage: SliceUsage,
    processor: SliceUsageProcessor
): Boolean {
    val result = produce(sliceUsage.usageInfo, mode, parentUsage) ?: listOf(sliceUsage)
    for (usage in result) {
        if (!processor.process(usage)) return false
    }
    return true
}