/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa

import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnostic
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.contract.contextual.CoeffectContextActions
import org.jetbrains.kotlin.fir.contract.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contract.contextual.diagnostics.CoeffectContextVerificationError
import org.jetbrains.kotlin.fir.contract.contextual.diagnostics.MissingCoeffectContextError
import org.jetbrains.kotlin.fir.contract.contextual.diagnostics.UnexpectedCoeffectContextError
import org.jetbrains.kotlin.fir.contract.contextual.family.CheckedExceptionContextError
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraph
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol

object CoeffectAnalyzer : AbstractCoeffectAnalyzer() {

    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter) {
        val function = graph.declaration as? FirFunction<*> ?: return

        val lambdaToOwnerFunction = mutableMapOf<FirAnonymousFunction, Pair<FirFunction<*>, AbstractFirBasedSymbol<*>>>()
        function.body?.accept(LambdaToOwnerFunctionCollector(lambdaToOwnerFunction))

        val actionsForNodes = mutableMapOf<CFGNode<*>, MutableList<CoeffectContextActions>>()
        graph.traverse(TraverseDirection.Forward, CoeffectContextActionsCollector(lambdaToOwnerFunction), actionsForNodes)

        val verifiableCoeffectFamilies = mutableSetOf<CoeffectFamily>()
        actionsForNodes.forEach { (_, actions) ->
            actions.forEach {
                if (it.verifier != null) verifiableCoeffectFamilies.add(it.verifier!!.family)
            }
        }
        if (verifiableCoeffectFamilies.isEmpty()) return

        val contextsForNodes = graph.collectDataForNode(
            TraverseDirection.Forward,
            CoeffectContextInfo.EMPTY,
            CoeffectContextResolver(actionsForNodes, verifiableCoeffectFamilies)
        )

        for ((node, actionsList) in actionsForNodes) {
            val prevNode = node.previousCfgNodes.firstOrNull() ?: node
            val data = contextsForNodes[prevNode] ?: continue

            for (actions in actionsList) {
                val verifier = actions.verifier ?: continue
                val context = data[verifier.family]
                val errors = verifier.verifyContext(context, function.session)

                node.fir.source?.let { source ->
                    errors.forEach { error ->
                        val firError = error.toFirError(source)
                        if (firError != null) reporter.report(firError)
                    }
                }
            }
        }
    }

    private fun CoeffectContextVerificationError.toFirError(source: FirSourceElement): FirDiagnostic<*>? = when (this) {
        is MissingCoeffectContextError -> FirErrors.MISSING_COEFFECT_CONTEXT.on(source)
        is UnexpectedCoeffectContextError -> FirErrors.UNEXPECTED_COEFFECT_CONTEXT.on(source)
        is CheckedExceptionContextError -> FirErrors.UNCHECKED_EXCEPTION.on(source, exceptionType)
        else -> null
    }
}