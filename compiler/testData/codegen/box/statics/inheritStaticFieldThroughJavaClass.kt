// TARGET_BACKEND: JVM
// DUMP_IR

// FILE: I.java
interface I {
    String S = "OK";
}

// FILE: J.java
public class J implements I {}

// FILE: box.kt
interface A {
    companion object : J() {
        val result = S
    }
}

fun box(): String = A.result
