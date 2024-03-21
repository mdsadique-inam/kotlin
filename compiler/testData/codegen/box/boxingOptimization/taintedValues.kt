// JVM_ABI_K1_K2_DIFF: KT-65038
// WITH_STDLIB

// Just make sure there's no VerifyError

fun getOrElse() =
        mapOf<String, Int>().getOrElse("foo") { 3 }

fun isNotEmpty(l: ArrayList<Int>) =
        l.iterator()?.hasNext() ?: false

fun box() = "OK"