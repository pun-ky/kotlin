/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.contract.contextual.*
import org.jetbrains.kotlin.fir.contract.contextual.declaration.CoeffectActionExtractors
import org.jetbrains.kotlin.fir.contracts.FirContractDescription
import org.jetbrains.kotlin.fir.contracts.effects
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirContractDescriptionOwner
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.isInPlaceLambda
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.resolve.dfa.contracts.createArgumentsMapping
import org.jetbrains.kotlin.fir.resolve.isInvoke
import org.jetbrains.kotlin.fir.resolve.transformers.contracts.ConeCoeffectEffectDeclaration
import org.jetbrains.kotlin.fir.resolve.transformers.contracts.ConeLambdaCoeffectEffectDeclaration
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

abstract class AbstractCoeffectAnalyzer : FirControlFlowChecker() {

    protected class LambdaToOwnerFunctionCollector(
        val lambdaToOwnerFunction: MutableMap<FirAnonymousFunction, Pair<FirFunction<*>, AbstractFirBasedSymbol<*>>>
    ) : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(
            functionCall: FirFunctionCall
        ) {
            val functionSymbol = functionCall.toResolvedCallableSymbol() ?: return
            val argumentMapping by lazy { createArgumentsMapping(functionCall)?.map { it.value to it.key }?.toMap() }
            val function = functionSymbol.fir as? FirFunction ?: return

            for (argument in functionCall.argumentList.arguments) {
                val expression = if (argument is FirWrappedArgumentExpression) argument.expression else argument
                if (expression.isInPlaceLambda()) {
                    val lambdaParameterIndex = argumentMapping?.get(expression) ?: continue
                    val lambdaSymbol = function.valueParameters[lambdaParameterIndex].symbol

                    lambdaToOwnerFunction[expression] = function to lambdaSymbol
                }
            }
            super.visitFunctionCall(functionCall)
        }
    }

    protected class CoeffectContextActionsCollector(
        val lambdaToOwnerFunction: MutableMap<FirAnonymousFunction, Pair<FirFunction<*>, AbstractFirBasedSymbol<*>>>
    ) : ControlFlowGraphVisitor<Unit, MutableMap<CFGNode<*>, MutableList<CoeffectContextActions>>>() {

        override fun visitNode(node: CFGNode<*>, data: MutableMap<CFGNode<*>, MutableList<CoeffectContextActions>>) {}

        override fun visitFunctionCallNode(node: FunctionCallNode, data: MutableMap<CFGNode<*>, MutableList<CoeffectContextActions>>) {
            val functionSymbol = node.fir.toResolvedCallableSymbol() ?: return

            if (functionSymbol.callableId.isInvoke()) {
                val receiverSymbol = node.fir.explicitReceiver?.toResolvedCallableSymbol() ?: return
                val function = (node.owner.enterNode as? FunctionEnterNode)?.fir ?: return

                collectLambdaCoeffectActions(node, function, receiverSymbol, data) { onOwnerCall?.extractActions(node.fir) }
            } else collectCoeffectActions(node, data) { onOwnerCall?.extractActions(node.fir) }
        }

        override fun visitFunctionEnterNode(node: FunctionEnterNode, data: MutableMap<CFGNode<*>, MutableList<CoeffectContextActions>>) {
            visitFunctionBoundaryNode(node, data) { onOwnerEnter?.extractActions(node.fir) }
        }

        override fun visitFunctionExitNode(node: FunctionExitNode, data: MutableMap<CFGNode<*>, MutableList<CoeffectContextActions>>) {
            visitFunctionBoundaryNode(node, data) { onOwnerExit?.extractActions(node.fir) }
        }

        inline fun visitFunctionBoundaryNode(
            node: CFGNode<FirFunction<*>>,
            data: MutableMap<CFGNode<*>, MutableList<CoeffectContextActions>>,
            extractor: CoeffectActionExtractors.() -> CoeffectContextActions?
        ) {
            val function = node.fir
            if (function.isInPlaceLambda()) {
                val (calledFunction, lambdaSymbol) = lambdaToOwnerFunction[function] ?: return
                collectLambdaCoeffectActions(node, calledFunction, lambdaSymbol, data, extractor)
            } else collectCoeffectActions(node, data, extractor)
        }

        inline fun collectCoeffectActions(
            node: CFGNode<*>,
            data: MutableMap<CFGNode<*>, MutableList<CoeffectContextActions>>,
            extractor: CoeffectActionExtractors.() -> CoeffectContextActions?
        ) {
            val effects = node.fir.contractDescription?.effects?.filterIsInstance<ConeCoeffectEffectDeclaration>()
            if (effects.isNullOrEmpty()) return

            for (effect in effects) {
                val actions = extractor(effect.actionExtractors) ?: continue
                data.getOrPut(node, ::mutableListOf) += actions
            }
        }

        inline fun collectLambdaCoeffectActions(
            node: CFGNode<*>,
            function: FirFunction<*>,
            lambdaSymbol: AbstractFirBasedSymbol<*>,
            data: MutableMap<CFGNode<*>, MutableList<CoeffectContextActions>>,
            extractor: CoeffectActionExtractors.() -> CoeffectContextActions?
        ) {
            val effects = function.contractDescription?.effects?.filterIsInstance<ConeLambdaCoeffectEffectDeclaration>()
            if (effects.isNullOrEmpty()) return

            for (effect in effects) {
                if (function.valueParameters[effect.lambda.parameterIndex].symbol != lambdaSymbol) continue
                val actions = extractor(effect.actionExtractors) ?: continue
                data.getOrPut(node, ::mutableListOf) += actions
            }
        }

        val FirElement.contractDescription: FirContractDescription?
            get() = when (this) {
                is FirFunction<*> -> (this as? FirContractDescriptionOwner)?.contractDescription
                is FirFunctionCall -> (this.toResolvedCallableSymbol()?.fir as? FirContractDescriptionOwner)?.contractDescription
                else -> null
            }
    }

    protected class CoeffectContextInfo(
        map: PersistentMap<CoeffectFamily, CoeffectContext> = persistentMapOf(),
    ) : ControlFlowInfo<CoeffectContextInfo, CoeffectFamily, CoeffectContext>(map) {

        companion object {
            val EMPTY = CoeffectContextInfo()
        }

        override val constructor: (PersistentMap<CoeffectFamily, CoeffectContext>) -> CoeffectContextInfo = ::CoeffectContextInfo

        override fun get(key: CoeffectFamily): CoeffectContext = super.get(key) ?: key.emptyContext

        fun merge(other: CoeffectContextInfo): CoeffectContextInfo {
            var result = this
            for (family in keys.union(other.keys)) {
                val context = family.combiner.merge(this[family], other[family])
                result = result.put(family, context)
            }
            return result
        }

        fun applyProvider(provider: CoeffectContextProvider?): CoeffectContextInfo {
            if (provider == null) return this
            val newContext = provider.provideContext(this[provider.family])
            return put(provider.family, newContext)
        }

        fun applyCleaner(cleaner: CoeffectContextCleaner?): CoeffectContextInfo {
            if (cleaner == null) return this
            val newContext = cleaner.cleanupContext(this[cleaner.family])
            return put(cleaner.family, newContext)
        }

        operator fun plus(provider: CoeffectContextProvider?): CoeffectContextInfo = applyProvider(provider)
        operator fun plus(cleaner: CoeffectContextCleaner?): CoeffectContextInfo = applyCleaner(cleaner)
    }

    protected class CoeffectContextResolver(
        val actionsForNodes: MutableMap<CFGNode<*>, MutableList<CoeffectContextActions>>,
        val verifiableCoeffectFamilies: Set<CoeffectFamily>
    ) : ControlFlowGraphVisitor<CoeffectContextInfo, Collection<CoeffectContextInfo>>() {

        private fun CoeffectFamily?.isVerifiable(): Boolean = this != null && this in verifiableCoeffectFamilies

        override fun visitNode(node: CFGNode<*>, data: Collection<CoeffectContextInfo>): CoeffectContextInfo {
            var dataForNode = if (data.isEmpty()) CoeffectContextInfo.EMPTY else data.reduce(CoeffectContextInfo::merge)
            val actions = actionsForNodes[node] ?: return dataForNode

            actions.forEach {
                if (it.provider?.family.isVerifiable()) dataForNode += it.provider
                if (it.cleaner?.family.isVerifiable()) dataForNode += it.cleaner
            }

            return dataForNode
        }
    }
}