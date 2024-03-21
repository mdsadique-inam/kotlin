// JVM_ABI_K1_K2_DIFF: KT-65038
// WITH_STDLIB
// IGNORE_BACKEND: WASM
// WASM_MUTE_REASON: STDLIB_COLLECTION_INHERITANCE
// DONT_TARGET_EXACT_BACKEND: NATIVE

open class A : ArrayList<String>()

class B : A()

fun box(): String {
    val b = B()
    b += "OK"
    return b.single()
}
