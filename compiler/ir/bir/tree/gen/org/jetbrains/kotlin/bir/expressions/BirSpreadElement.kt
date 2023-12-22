/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

// This file was generated automatically. See compiler/ir/bir.tree/tree-generator/ReadMe.md.
// DO NOT MODIFY IT MANUALLY.

package org.jetbrains.kotlin.bir.expressions

import org.jetbrains.kotlin.bir.*

/**
 * A leaf IR tree element.
 *
 * Generated from: [org.jetbrains.kotlin.bir.generator.BirTree.spreadElement]
 */
abstract class BirSpreadElement(elementClass: BirElementClass) : BirImplElementBase(elementClass), BirElement, BirVarargElement {
    abstract var expression: BirExpression?

    override fun <D> acceptChildren(visitor: BirElementVisitor<D>, data: D) {
        expression?.accept(data, visitor)
    }

    companion object : BirElementClass(BirSpreadElement::class.java, 52, true)
}
