// JVM_ABI_K1_K2_DIFF: KT-65038
// MODULE: lib
// FILE: A.kt
typealias Bar<T> = (T) -> String

class Foo<out T>(val t: T) {

    fun baz(b: Bar<T>) = b(t)
}

// MODULE: main(lib)
// FILE: B.kt
class FooTest {
    fun baz(): String {
        val b: Bar<String> = { "OK" }
        return Foo("").baz(b)
    }
}

fun box(): String =
        FooTest().baz()