/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.common.arguments

import java.io.Serializable
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField

@kotlinx.serialization.Serializable
abstract class CommonToolArguments : Freezable(), Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID = 0L
    }

    var freeArgs: List<String> = emptyList()
        set(value) {
            checkFrozen()
            field = value
        }

    @Transient
    @kotlinx.serialization.Transient
    var errors: ArgumentParseErrors? = null

    var help = false
        set(value) {
            checkFrozen()
            field = value
        }

    var extraHelp = false
        set(value) {
            checkFrozen()
            field = value
        }

    var version = false
        set(value) {
            checkFrozen()
            field = value
        }

    var verbose = false
        set(value) {
            checkFrozen()
            field = value
        }

    var suppressWarnings = false
        set(value) {
            checkFrozen()
            field = value
        }

    var allWarningsAsErrors = false
        set(value) {
            checkFrozen()
            field = value
        }

    var internalArguments: List<InternalArgument> = emptyList()
        set(value) {
            checkFrozen()
            field = value
        }

    // This is a hack to workaround an issue that incremental compilation does not recompile CLI arguments classes after the change in
    // the previous commit. This method can be removed after some time.
    override fun equals(other: Any?): Boolean = super.equals(other)
}


/**
 * An argument which should be passed to Kotlin compiler to enable [this] compiler option
 */
val KProperty1<out CommonCompilerArguments, *>.cliArgument: String
    get() {
        val javaField = javaField
            ?: error("Java field should be present for $this")
        val argumentAnnotation = javaField.getAnnotation<Argument>(Argument::class.java)
        return argumentAnnotation.value
    }