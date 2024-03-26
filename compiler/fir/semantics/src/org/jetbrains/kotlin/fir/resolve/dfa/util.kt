/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dfa

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.originalOrSelf
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeContext

fun TypeStatement?.smartCastedType(context: ConeTypeContext, originalType: ConeKotlinType): ConeKotlinType =
    if (this != null && exactType.isNotEmpty()) {
        context.intersectTypes(exactType.toMutableList().also { it += originalType })
    } else {
        originalType
    }

@DfaInternals
fun FirOperation.isEq(): Boolean {
    return when (this) {
        FirOperation.EQ, FirOperation.IDENTITY -> true
        FirOperation.NOT_EQ, FirOperation.NOT_IDENTITY -> false
        else -> throw IllegalArgumentException("$this should not be there")
    }
}

fun FirVariable.isInCurrentOrFriendModule(session: FirSession): Boolean {
    val propertyModuleData = originalOrSelf().moduleData
    val currentModuleData = session.moduleData
    return propertyModuleData == currentModuleData ||
            propertyModuleData in currentModuleData.friendDependencies ||
            propertyModuleData in currentModuleData.allDependsOnDependencies
}
