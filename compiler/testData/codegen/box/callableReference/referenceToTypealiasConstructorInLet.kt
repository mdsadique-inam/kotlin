// JVM_ABI_K1_K2_DIFF: KT-65038
// TARGET_BACKEND: JVM_IR
// WITH_STDLIB
// FULL_JDK

import java.util.EnumMap

enum class SomeEnum {
    A, B
}

typealias SomeMap = EnumMap<SomeEnum, String>

fun test(oldMap: SomeMap, key: SomeEnum): String {
    val newMap = oldMap.let(::SomeMap)
    return newMap.getValue(key)
}

fun box(): String {
    val map = EnumMap(mapOf(SomeEnum.A to "OK"))
    return test(map, SomeEnum.A)
}
