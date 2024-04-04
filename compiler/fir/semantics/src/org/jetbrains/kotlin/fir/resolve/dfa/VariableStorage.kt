/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dfa

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.fir.util.SetMultimap
import org.jetbrains.kotlin.fir.util.setMultimapOf

class VariableStorage {
    // This is basically a set, since it maps each key to itself. The only point of having it as a map
    // is to deduplicate equal instances with lookups. The impact of that is questionable, but whatever.
    private val realVariables: MutableMap<RealVariable, RealVariable> = HashMap()

    private val memberVariables: SetMultimap<RealVariable, RealVariable> = setMultimapOf()

    fun getAllLocalVariables(): List<RealVariable> =
        realVariables.values.filter { (it.symbol as? FirPropertySymbol)?.isLocal == true }

    /**
     * Retrieve a [DataFlowVariable] representing the given [FirElement]. If [fir] is a property access (read, assignment, or
     * declaration), return a [RealVariable], otherwise a [SyntheticVariable].
     *
     * @param fir An expression, a variable assignment, or a variable declaration. Although it is technically allowed to create
     * variables for other kinds of statements, it is meaningless.
     *
     * @param createReal Whether to create a new [RealVariable] for [fir] if one has never been created before. This is a
     * performance optimization: always setting this to true is semantically correct, so it should be set to false whenever
     * this does not result in missing statements.
     *
     * @param unwrapAlias When multiple [RealVariable]s are known to have the same value in every execution, this lambda
     * can map them to some "representative" variable so that type information about all these variables is shared. It can also
     * return null to abort the entire [get] operation, making it also return null.
     *
     * @param unwrapAliasInReceivers Same as [unwrapAlias], but used when looking up [RealVariable]s for receivers of [fir]
     * if it is a qualified access expression.
     */
    fun get(
        fir: FirElement,
        createReal: Boolean,
        unwrapAlias: (RealVariable) -> RealVariable?,
        unwrapAliasInReceivers: (RealVariable) -> RealVariable? = unwrapAlias,
    ): DataFlowVariable? {
        val unwrapped = fir.unwrapElement()
        val isReceiver = unwrapped is FirThisReceiverExpression
        val symbol = when (unwrapped) {
            is FirDeclaration -> unwrapped.symbol
            is FirResolvedQualifier -> unwrapped.symbol
            is FirResolvable -> unwrapped.calleeReference.symbol
            else -> null
        }?.takeIf {
            isReceiver || it is FirClassSymbol || (it is FirVariableSymbol && it !is FirSyntheticPropertySymbol)
        } ?: return SyntheticVariable(unwrapped)

        val qualifiedAccess = unwrapped as? FirQualifiedAccessExpression
        val dispatchReceiverVar = qualifiedAccess?.dispatchReceiver?.let {
            (get(it, createReal, unwrapAliasInReceivers) ?: return null) as? RealVariable ?: return SyntheticVariable(unwrapped)
        }
        val extensionReceiverVar = qualifiedAccess?.extensionReceiver?.let {
            (get(it, createReal, unwrapAliasInReceivers) ?: return null) as? RealVariable ?: return SyntheticVariable(unwrapped)
        }
        val prototype = RealVariable(symbol.unwrapFakeOverridesIfNecessary(), isReceiver, dispatchReceiverVar, extensionReceiverVar)
        val real = if (createReal) rememberWithKnownReceivers(prototype) else realVariables[prototype] ?: return null
        return unwrapAlias(real)
    }

    /** Store a reference to a variable so that [get] can return it even if `createReal` is false. */
    fun remember(variable: RealVariable): RealVariable =
        rememberWithKnownReceivers(
            variable.copy(
                dispatchReceiver = variable.dispatchReceiver?.let(::remember),
                extensionReceiver = variable.extensionReceiver?.let(::remember),
            )
        )

    private fun rememberWithKnownReceivers(variable: RealVariable): RealVariable =
        realVariables.getOrPut(variable) {
            variable.dispatchReceiver?.let { memberVariables.put(it, variable) }
            variable.extensionReceiver?.let { memberVariables.put(it, variable) }
            variable
        }

    /**
     * Call a lambda with every known [RealVariable] that represents a member property of another [RealVariable].
     *
     * @param to If not null, additionally replace these variables' receivers with the new value, and pass the modified member
     * to the lambda as the second argument.
     *
     * For example, if [from] represents `x`, [to] represents `y`, and there is a known [RealVariable] representing `x.p`, then
     * [processMember] will be called that variable as the first argument and a new variable representing `y.p` as the second.
     */
    fun replaceReceiverReferencesInMembers(from: RealVariable, to: RealVariable?, processMember: (RealVariable, RealVariable?) -> Unit) {
        for (member in memberVariables[from]) {
            val remapped = to?.let {
                val dispatchReceiver = if (member.dispatchReceiver == from) to else member.dispatchReceiver
                val extensionReceiver = if (member.extensionReceiver == from) to else member.extensionReceiver
                rememberWithKnownReceivers(member.copy(dispatchReceiver = dispatchReceiver, extensionReceiver = extensionReceiver))
            }
            processMember(member, remapped)
        }
    }
}

private tailrec fun FirElement.unwrapElement(): FirElement {
    return when (this) {
        is FirWhenSubjectExpression -> whenRef.value.let { it.subjectVariable ?: it.subject ?: return this }.unwrapElement()
        is FirSmartCastExpression -> originalExpression.unwrapElement()
        is FirSafeCallExpression -> selector.unwrapElement()
        is FirCheckedSafeCallSubject -> originalReceiverRef.value.unwrapElement()
        is FirCheckNotNullCall -> argument.unwrapElement()
        is FirDesugaredAssignmentValueReferenceExpression -> expressionRef.value.unwrapElement()
        is FirVariableAssignment -> lValue.unwrapElement()
        else -> this
    }
}

private fun FirBasedSymbol<*>.unwrapFakeOverridesIfNecessary(): FirBasedSymbol<*> {
    if (this !is FirCallableSymbol) return this
    // This is necessary only for sake of optimizations because this is a really hot place.
    // Not having `dispatchReceiverType` means that this is a local/top-level property that can't be a fake override.
    // And at the same time, checking a field is much faster than a couple of attributes (0.3 secs at MT Full Kotlin)
    if (this.dispatchReceiverType == null) return this
    return this.unwrapFakeOverrides()
}
