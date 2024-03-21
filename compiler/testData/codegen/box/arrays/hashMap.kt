// JVM_ABI_K1_K2_DIFF: KT-65038
// KJS_WITH_FULL_RUNTIME
operator fun HashMap<String, Int?>.set(index: String, elem: Int?) {
    this.put(index, elem)
}

fun box(): String {
    val s = HashMap<String, Int?>()
    s["239"] = 239
    return if (s["239"] == 239) "OK" else "Fail"
}
