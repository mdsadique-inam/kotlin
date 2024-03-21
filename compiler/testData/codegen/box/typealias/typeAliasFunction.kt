// JVM_ABI_K1_K2_DIFF: KT-65038
typealias F<X> = (X?) -> X

fun <T> invoke(f: F<T>) = f(null)

fun box() = invoke<String> { it ?: "OK" }
