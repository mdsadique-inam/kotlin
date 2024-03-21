// JVM_ABI_K1_K2_DIFF: KT-65038
// WITH_STDLIB

open class A : HashMap<String, String>()

class B : A()
