// JVM_ABI_K1_K2_DIFF: KT-65038
// TARGET_BACKEND: JVM_IR

fun f(x: Throwable) { throw x }
fun f(x: Exception) { throw x }
fun f(x: RuntimeException) { throw x }
fun f(x: Error) { throw x }
fun f(x: AssertionError) { throw x }

// 0 CHECKCAST
