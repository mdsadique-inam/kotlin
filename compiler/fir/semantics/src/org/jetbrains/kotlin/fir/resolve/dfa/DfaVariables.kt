/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dfa

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isFinal
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.types.SmartcastStability

sealed class DataFlowVariable

data class SyntheticVariable(val fir: FirExpression) : DataFlowVariable()

data class RealVariable(
    val symbol: FirBasedSymbol<*>,
    val isReceiver: Boolean,
    val dispatchReceiver: RealVariable?,
    val extensionReceiver: RealVariable?,
) : DataFlowVariable() {
    companion object {
        fun local(symbol: FirVariableSymbol<*>): RealVariable =
            RealVariable(symbol, isReceiver = false, dispatchReceiver = null, extensionReceiver = null)

        fun receiver(symbol: FirBasedSymbol<*>): RealVariable =
            RealVariable(symbol, isReceiver = true, dispatchReceiver = null, extensionReceiver = null)
    }

    override fun toString(): String =
        (if (isReceiver) "this@" else "") + when (symbol) {
            is FirClassSymbol<*> -> "${symbol.classId}"
            is FirCallableSymbol<*> -> "${symbol.callableId}"
            else -> "$symbol"
        } + when {
            dispatchReceiver != null && extensionReceiver != null -> "(${dispatchReceiver}, ${extensionReceiver})"
            dispatchReceiver != null || extensionReceiver != null -> "(${dispatchReceiver ?: extensionReceiver})"
            else -> ""
        }

    val originalType: ConeKotlinType
        get() = when (symbol) {
            is FirClassSymbol<*> -> symbol.defaultType()
            is FirCallableSymbol<*> -> if (isReceiver) symbol.resolvedReceiverTypeRef?.type else symbol.resolvedReturnType
            else -> null
        } ?: error("RealVariable has incorrect symbol $symbol")

    private var cachedSymbolStability: SmartcastStability? = null
    private var unstableInOtherModules = false
    private var unstableOnOpenReceivers = false

    fun getStability(flow: Flow, session: FirSession): SmartcastStability {
        if (!isReceiver) {
            val symbolStability = getSymbolStability()
            if (symbolStability != SmartcastStability.STABLE_VALUE) return symbolStability
            if (unstableOnOpenReceivers && dispatchReceiver?.hasFinalType(flow, session) != true)
                return SmartcastStability.PROPERTY_WITH_GETTER
            if (unstableInOtherModules && !(symbol.fir as FirVariable).isInCurrentOrFriendModule(session))
                return SmartcastStability.ALIEN_PUBLIC_PROPERTY
            // Members of unstable values should always be unstable.
            dispatchReceiver?.getStability(flow, session)?.takeIf { it != SmartcastStability.STABLE_VALUE }?.let { return it }
            // No need to check extension receiver, as properties with one cannot be stable by symbol stability.
        }
        return SmartcastStability.STABLE_VALUE
    }

    private fun hasFinalType(flow: Flow, session: FirSession): Boolean =
        originalType.isFinal(session) || flow.getTypeStatement(this)?.exactType?.any { it.isFinal(session) } == true

    private fun getSymbolStability(): SmartcastStability {
        return cachedSymbolStability ?: when (val fir = symbol.fir) {
            !is FirVariable -> SmartcastStability.STABLE_VALUE // named object or containing class for a static field reference
            is FirEnumEntry -> SmartcastStability.STABLE_VALUE
            is FirErrorProperty -> SmartcastStability.STABLE_VALUE
            is FirValueParameter -> SmartcastStability.STABLE_VALUE
            is FirBackingField -> if (fir.isVal) SmartcastStability.STABLE_VALUE else SmartcastStability.MUTABLE_PROPERTY
            is FirField -> if (fir.isFinal)
                SmartcastStability.STABLE_VALUE.also { unstableInOtherModules = true }
            else
                SmartcastStability.MUTABLE_PROPERTY
            is FirProperty -> when {
                fir.isExpect -> SmartcastStability.EXPECT_PROPERTY
                fir.delegate != null -> SmartcastStability.DELEGATED_PROPERTY
                // Local vars are only *sometimes* unstable (when there are concurrent assignments). `FirDataFlowAnalyzer`
                // will check that at each use site individually and produce `CAPTURED_VARIABLE` instead when necessary.
                fir.isLocal -> SmartcastStability.STABLE_VALUE
                fir.isVar -> SmartcastStability.MUTABLE_PROPERTY
                fir.receiverParameter != null -> SmartcastStability.PROPERTY_WITH_GETTER
                fir.getter !is FirDefaultPropertyAccessor? -> SmartcastStability.PROPERTY_WITH_GETTER
                fir.visibility == Visibilities.Private -> SmartcastStability.STABLE_VALUE
                else -> SmartcastStability.STABLE_VALUE.also {
                    unstableInOtherModules = true
                    unstableOnOpenReceivers = !fir.isFinal
                }
            }
        }.also { cachedSymbolStability = it }
    }
}

private fun ConeKotlinType.isFinal(session: FirSession): Boolean = when (this) {
    is ConeFlexibleType -> lowerBound.isFinal(session)
    is ConeDefinitelyNotNullType -> original.isFinal(session)
    is ConeClassLikeType -> toSymbol(session)?.fullyExpandedClass(session)?.isFinal == true
    // An intersection type can only be final if it's not instantiable, otherwise there would be no need to intersect anything.
    //   val x: Interface = ...
    //   if (x is ClassImplementingIt) { /* x: ClassImplementingIt -- `& Interface` is redundant */ }
    //   if (x is ClassNotImplementingIt) { /* x: Interface & ClassNotImplementingIt -- this code is unreachable */ }
    is ConeIntersectionType -> intersectedTypes.any { it.isFinal(session) }
    else -> false
}
