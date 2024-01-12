/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.klib

enum class KlibCompilerEdition(
    val version: String, val args: List<String> = emptyList(),
) {
    CURRENT(KotlinVersion.CURRENT.toString()),
    LATEST_RELEASE("1.9.23"),
    OLDEST_SUPPORTED("1.6.21")
}

/**
 *      Intermediate -> Bottom
 *          \           /
 *              Main
 *
 * For `Bw*` = `Backward*` cases with check that we can replace klib that built with a newer compiler version in runtime
 * and it works.
 * For `Fw*` = `Forward*` cases we check that the klib built with older compiler can be used in runtime.
 */
enum class KlibCompilerChangeScenario(
    val bottomV1: KlibCompilerEdition,
    val bottomV2: KlibCompilerEdition,
    val intermediate: KlibCompilerEdition,
) {
    NoChange(KlibCompilerEdition.CURRENT, KlibCompilerEdition.CURRENT, KlibCompilerEdition.CURRENT),
    BwLatestWithCurrent(KlibCompilerEdition.LATEST_RELEASE, KlibCompilerEdition.CURRENT, KlibCompilerEdition.CURRENT),
    BwLatestWithLatest(KlibCompilerEdition.LATEST_RELEASE, KlibCompilerEdition.CURRENT, KlibCompilerEdition.LATEST_RELEASE),
    BwOldestWithCurrent(KlibCompilerEdition.OLDEST_SUPPORTED, KlibCompilerEdition.CURRENT, KlibCompilerEdition.CURRENT),
    BwOldestWithOldest(KlibCompilerEdition.OLDEST_SUPPORTED, KlibCompilerEdition.CURRENT, KlibCompilerEdition.OLDEST_SUPPORTED),
    FwLatest(KlibCompilerEdition.CURRENT, KlibCompilerEdition.LATEST_RELEASE, KlibCompilerEdition.CURRENT),
}