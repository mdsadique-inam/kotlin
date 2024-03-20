/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.platform.wasm

import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.toTargetPlatform
import org.jetbrains.kotlin.platform.WasmPlatform as CoreWasmPlatform

abstract class WasmPlatform : CoreWasmPlatform() {
    override val oldFashionedDescription: String
        get() = "Wasm"
}

object WasmPlatformUnspecifiedTarget : WasmPlatform() {
    override val targetName: String
        get() = "general"
}

class WasmPlatformWithTarget(val target: WasmTarget) : WasmPlatform() {
    override val targetName: String
        get() = target.name
}

object WasmPlatforms {
    private val platforms: Map<WasmTarget, TargetPlatform> =
        WasmTarget.entries.associateWith { WasmPlatformWithTarget(it).toTargetPlatform() }

    @Suppress("DEPRECATION_ERROR")
    val unspecifiedWasmPlatform: TargetPlatform
        get() = Default

    val wasmJs = platforms[WasmTarget.JS]!!
    val wasmWasi = platforms[WasmTarget.WASI]!!

    fun wasmPlatformByTargetVersion(targetVersion: WasmTarget): TargetPlatform =
        platforms[targetVersion]!!

    val allWasmPlatforms: List<TargetPlatform> = listOf(unspecifiedWasmPlatform) + platforms.values

    object Default : TargetPlatform(setOf(WasmPlatformUnspecifiedTarget))
}

fun TargetPlatform?.isWasm(): Boolean = this?.singleOrNull() is WasmPlatform