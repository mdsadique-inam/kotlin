/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.utils

import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import java.io.ByteArrayOutputStream
import java.io.File

internal class PlistBuddyRunner(
    private val plist: File,
    private val execOperations: ExecOperations,
) {
    private val commands = mutableListOf<String>()
    var ignoreExitValue = false

    fun run(): ExecResult = execOperations.exec { exec ->
        exec.executable = "/usr/libexec/PlistBuddy"
        commands.forEach {
            exec.args("-c", it)
        }
        exec.args(plist.absolutePath)
        exec.isIgnoreExitValue = ignoreExitValue
        // Hide process output.
        val dummyStream = ByteArrayOutputStream()
        exec.standardOutput = dummyStream
        exec.errorOutput = dummyStream
    }

    fun add(entry: String, value: String) = commands.add("Add \"$entry\" string \"$value\"")
    fun set(entry: String, value: String) = commands.add("Set \"$entry\" \"$value\"")
    fun delete(entry: String) = commands.add("Delete \"$entry\"")
}

// Runs the PlistBuddy utility with the given commands to configure the given plist file.
internal fun processPlist(plist: File, execOperations: ExecOperations, commands: PlistBuddyRunner.() -> Unit) =
    PlistBuddyRunner(plist, execOperations).apply {
        commands()
    }.run()