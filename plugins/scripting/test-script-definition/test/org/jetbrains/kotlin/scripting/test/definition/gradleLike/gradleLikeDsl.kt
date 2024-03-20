/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.test.definition.gradleLike

@JvmOverloads
fun Project.projectApi(body: (Int) -> Int = { it }): Int = body(42)

fun staticApi(): Int = 42
