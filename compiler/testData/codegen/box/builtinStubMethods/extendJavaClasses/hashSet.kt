// JVM_ABI_K1_K2_DIFF: KT-65038
// IGNORE_BACKEND: WASM
// WASM_MUTE_REASON: STDLIB_COLLECTION_INHERITANCE
// KJS_WITH_FULL_RUNTIME
// DONT_TARGET_EXACT_BACKEND: NATIVE
class A : HashSet<Long>()

fun box(): String {
    val a = A()
    val b = A()

    a.iterator()

    a.add(0L)
    a.remove(0L)

    a.addAll(b)
    a.removeAll(b)
    a.retainAll(b)
    a.clear()

    return "OK"
}
