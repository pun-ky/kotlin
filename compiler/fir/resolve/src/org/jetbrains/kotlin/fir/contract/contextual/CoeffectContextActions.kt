/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contract.contextual

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.contract.contextual.diagnostics.CoeffectContextVerificationError

interface CoeffectContextCleaner {
    val family: CoeffectFamily
    fun cleanupContext(context: CoeffectContext): CoeffectContext
}

interface CoeffectContextProvider {
    val family: CoeffectFamily
    fun provideContext(context: CoeffectContext): CoeffectContext
}

interface CoeffectContextVerifier {
    val family: CoeffectFamily
    fun verifyContext(context: CoeffectContext, session: FirSession): List<CoeffectContextVerificationError>
}

class CoeffectContextActions(
    val provider: CoeffectContextProvider? = null,
    val verifier: CoeffectContextVerifier? = null,
    val cleaner: CoeffectContextCleaner? = null
)