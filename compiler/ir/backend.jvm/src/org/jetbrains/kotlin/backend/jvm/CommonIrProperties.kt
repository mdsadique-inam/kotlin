/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.ir.declarations.IrAttributeContainer
import org.jetbrains.kotlin.ir.get
import org.jetbrains.kotlin.ir.irDynamicProperty
import org.jetbrains.kotlin.ir.set
import org.jetbrains.org.objectweb.asm.Type

val LocalClassType by irDynamicProperty<IrAttributeContainer, Type>()

fun IrAttributeContainer.getLocalClassType(): Type? =
    attributeOwnerId[LocalClassType]

fun IrAttributeContainer.putLocalClassType(value: Type) {
    attributeOwnerId[LocalClassType] = value
}
