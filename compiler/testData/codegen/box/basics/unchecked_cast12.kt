// IGNORE_NATIVE: optimizationMode=OPT
// IGNORE_BACKEND: JS_IR, JS_IR_ES6

fun box(): String {
    try {
        val x = ("1" as Comparable<Any>).compareTo(2)
        return "FAIL: $x"
    } catch (e: ClassCastException) {
        return "OK"
    }
}
