import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems.jar

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    implementation(commonDependency("org.jetbrains.intellij.deps:asm-all"))
    implementation(commonDependency("org.apache.commons:commons-text"))

    implementation(project(":tools:kotlinp-jvm"))
    implementation(project(":kotlin-metadata-jvm"))
    implementation(project(":kotlin-metadata"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

runtimeJar {
    manifest.attributes["Main-Class"] = "org.jetbrains.kotlin.abicmp.AbiComparatorMain"

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
             configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
         })
}