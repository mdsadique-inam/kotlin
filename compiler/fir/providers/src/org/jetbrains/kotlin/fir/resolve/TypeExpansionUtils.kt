/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.utils.expandedConeType
import org.jetbrains.kotlin.fir.resolve.substitution.AbstractConeSubstitutor
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.util.WeakPair
import org.jetbrains.kotlin.util.component1
import org.jetbrains.kotlin.util.component2

/**
 * Compute the recursive type-alias expansion in the given type.
 *
 * A type of an expect class, that is actualized by a typealias will be expanded to the expansion of the typealias,
 * when supplied with the session of actual.
 *
 * See `/docs/fir/k2_kmp.md`
 *
 * @param useSiteSession Session to be used for classifier lookups, see [toSymbol]
 * @return Type, that is expanded to the concrete class type w.r.t to the [useSiteSession]
 */
fun ConeClassLikeType.fullyExpandedType(
    useSiteSession: FirSession,
    expandedConeType: (FirTypeAlias) -> ConeClassLikeType? = { alias ->
        alias.lazyResolveToPhase(FirResolvePhase.SUPER_TYPES)
        alias.expandedConeType
    },
): ConeClassLikeType {
    if (this is ConeClassLikeTypeImpl) {
        val (cachedSession, cachedExpandedType) = cachedExpandedType
        if (cachedSession === useSiteSession && cachedExpandedType != null) {
            return cachedExpandedType
        }

        val computedExpandedType = fullyExpandedTypeNoCache(useSiteSession, expandedConeType)
        this.cachedExpandedType = WeakPair(useSiteSession, computedExpandedType)
        return computedExpandedType
    }

    return fullyExpandedTypeNoCache(useSiteSession, expandedConeType)
}

/**
 * @see fullyExpandedType
 */
fun ConeKotlinType.fullyExpandedType(
    useSiteSession: FirSession
): ConeKotlinType = when (this) {
    is ConeDynamicType -> this
    is ConeFlexibleType ->
        ConeFlexibleType(lowerBound.fullyExpandedType(useSiteSession), upperBound.fullyExpandedType(useSiteSession))
    is ConeClassLikeType -> fullyExpandedType(useSiteSession)
    else -> this
}

/**
 * @see fullyExpandedType
 */
fun ConeSimpleKotlinType.fullyExpandedType(
    useSiteSession: FirSession
): ConeSimpleKotlinType = when (this) {
    is ConeClassLikeType -> fullyExpandedType(useSiteSession)
    else -> this
}

private fun ConeClassLikeType.fullyExpandedTypeNoCache(
    useSiteSession: FirSession,
    expandedConeType: (FirTypeAlias) -> ConeClassLikeType?,
): ConeClassLikeType {
    val directExpansionType = directExpansionType(useSiteSession, expandedConeType) ?: return this
    return directExpansionType.fullyExpandedType(useSiteSession, expandedConeType)
}

fun ConeClassLikeType.directExpansionType(
    useSiteSession: FirSession,
    expandedConeType: (FirTypeAlias) -> ConeClassLikeType? = { alias ->
        alias.lazyResolveToPhase(FirResolvePhase.SUPER_TYPES)
        alias.expandedConeType
    },
): ConeClassLikeType? {
    if (this is ConeErrorType) return null
    val typeAliasSymbol = lookupTag.toSymbol(useSiteSession) as? FirTypeAliasSymbol ?: return null
    val typeAlias = typeAliasSymbol.fir

    val resultType = expandedConeType(typeAlias)
        ?.applyNullabilityFrom(useSiteSession, this)
        ?.applyAttributesFrom(this)
        ?: return null

    if (resultType.typeArguments.isEmpty()) return resultType
    return mapTypeAliasArguments(typeAlias, this, resultType, useSiteSession) as? ConeClassLikeType
}

private fun ConeClassLikeType.applyNullabilityFrom(
    session: FirSession,
    abbreviation: ConeClassLikeType
): ConeClassLikeType {
    if (abbreviation.isMarkedNullable) return withNullability(ConeNullability.NULLABLE, session.typeContext)
    return this
}

private fun ConeClassLikeType.applyAttributesFrom(
    abbreviation: ConeClassLikeType
): ConeClassLikeType {
    val combinedAttributes = attributes.add(abbreviation.attributes)
    return withAttributes(combinedAttributes)
}

private fun mapTypeAliasArguments(
    typeAlias: FirTypeAlias,
    abbreviatedType: ConeClassLikeType,
    resultingType: ConeClassLikeType,
    useSiteSession: FirSession,
): ConeKotlinType {
    if (typeAlias.typeParameters.isNotEmpty() && abbreviatedType.typeArguments.isEmpty()) {
        return resultingType.lookupTag.constructClassType(ConeTypeProjection.EMPTY_ARRAY, resultingType.isNullable)
    }
    val typeAliasMap = typeAlias.typeParameters.map { it.symbol }.zip(abbreviatedType.typeArguments).toMap()

    val substitutor = object : AbstractConeSubstitutor(useSiteSession.typeContext) {
        override fun substituteType(type: ConeKotlinType): ConeKotlinType? {
            return null
        }

        override fun substituteArgument(projection: ConeTypeProjection, index: Int): ConeTypeProjection? {
            val type = (projection as? ConeKotlinTypeProjection)?.type ?: return null
            val symbol = (type as? ConeTypeParameterType)?.lookupTag?.symbol ?: return super.substituteArgument(
                projection,
                index
            )
            val mappedProjection = typeAliasMap[symbol] ?: return super.substituteArgument(projection, index)
            var mappedType = (mappedProjection as? ConeKotlinTypeProjection)?.type.updateNullabilityIfNeeded(type)
            mappedType = when (mappedType) {
                is ConeErrorType,
                is ConeClassLikeTypeImpl,
                is ConeDefinitelyNotNullType,
                is ConeTypeParameterTypeImpl,
                is ConeFlexibleType -> {
                    mappedType.withAttributes(type.attributes.add(mappedType.attributes))
                }
                null -> return mappedProjection
                else -> mappedType
            }

            fun convertProjectionKindToConeTypeProjection(projectionKind: ProjectionKind): ConeTypeProjection {
                return when (projectionKind) {
                    ProjectionKind.STAR -> ConeStarProjection
                    ProjectionKind.IN -> ConeKotlinTypeProjectionIn(mappedType)
                    ProjectionKind.OUT -> ConeKotlinTypeProjectionOut(mappedType)
                    ProjectionKind.INVARIANT -> mappedType
                }
            }

            if (mappedProjection.kind == projection.kind) {
                return convertProjectionKindToConeTypeProjection(mappedProjection.kind)
            }

            if (mappedProjection.kind == ProjectionKind.STAR || projection.kind == ProjectionKind.STAR) {
                return ConeStarProjection
            }

            if (mappedProjection.kind == ProjectionKind.INVARIANT) {
                return convertProjectionKindToConeTypeProjection(projection.kind)
            }

            if (projection.kind == ProjectionKind.INVARIANT) {
                return convertProjectionKindToConeTypeProjection(mappedProjection.kind)
            }

            return ConeKotlinTypeConflictingProjection(mappedType)
        }
    }

    return substitutor.substituteOrSelf(resultingType)
}

/**
 * @see fullyExpandedType
 */
fun FirTypeAlias.fullyExpandedConeType(useSiteSession: FirSession): ConeClassLikeType? {
    return expandedConeType?.fullyExpandedType(useSiteSession)
}

/**
 * @see fullyExpandedType
 */
fun FirTypeAlias.fullyExpandedClass(session: FirSession): FirClassLikeDeclaration? {
    return fullyExpandedConeType(session)?.toSymbol(session)?.fir
}