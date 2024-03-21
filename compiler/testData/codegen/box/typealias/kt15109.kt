// JVM_ABI_K1_K2_DIFF: KT-65038
open class A(private val s: String = "") {
    fun foo() = s
}

typealias B = A

class C : B(s = "OK")

fun box() = C().foo()
