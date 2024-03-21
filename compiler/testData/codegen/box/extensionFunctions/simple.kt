// JVM_ABI_K1_K2_DIFF: KT-65038
// KJS_WITH_FULL_RUNTIME
fun StringBuilder.first() = this.get(0)

fun foo() = StringBuilder("foo").first()

fun box() = if (foo() == 'f') "OK" else "Fail ${foo()}"
