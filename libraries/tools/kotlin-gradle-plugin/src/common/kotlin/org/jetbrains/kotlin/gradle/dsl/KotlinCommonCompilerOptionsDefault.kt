// DO NOT EDIT MANUALLY!
// Generated by org/jetbrains/kotlin/generators/arguments/GenerateGradleOptions.kt
// To regenerate run 'generateGradleOptions' task
@file:Suppress("RemoveRedundantQualifierName", "Deprecation", "DuplicatedCode")

package org.jetbrains.kotlin.gradle.dsl

internal abstract class KotlinCommonCompilerOptionsDefault @javax.inject.Inject constructor(
    objectFactory: org.gradle.api.model.ObjectFactory
) : org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerToolOptionsDefault(objectFactory), org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions {

    override val apiVersion: org.gradle.api.provider.Property<org.jetbrains.kotlin.gradle.dsl.KotlinVersion> =
        objectFactory.property(org.jetbrains.kotlin.gradle.dsl.KotlinVersion::class.java)

    override val languageVersion: org.gradle.api.provider.Property<org.jetbrains.kotlin.gradle.dsl.KotlinVersion> =
        objectFactory.property(org.jetbrains.kotlin.gradle.dsl.KotlinVersion::class.java)

    override val optIn: org.gradle.api.provider.ListProperty<kotlin.String> =
        objectFactory.listProperty(kotlin.String::class.java).convention(emptyList<String>())

    @Deprecated(message = "Compiler flag -Xuse-k2 is deprecated; please use language version 2.0 instead", level = DeprecationLevel.WARNING)
    override val useK2: org.gradle.api.provider.Property<kotlin.Boolean> =
        objectFactory.property(kotlin.Boolean::class.java).convention(false)

    internal fun fillCompilerArguments(args: org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments) {
        super.fillCompilerArguments(args)
        args.apiVersion = apiVersion.orNull?.version
        args.languageVersion = languageVersion.orNull?.version
        args.optIn = optIn.get().toTypedArray()
        args.useK2 = useK2.get()
    }

    internal fun fillDefaultValues(args: org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments) {
        super.fillDefaultValues(args)
        args.apiVersion = null
        args.languageVersion = null
        args.optIn = emptyList<String>().toTypedArray()
        args.useK2 = false
    }
}
