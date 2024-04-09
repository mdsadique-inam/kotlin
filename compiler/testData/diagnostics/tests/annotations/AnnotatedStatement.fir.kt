// ISSUE: KT-67014
// WITH_STDLIB
// FILE: JavaAnn.java

public @interface JavaAnn {}

// FILE: test.kt
annotation class KotlinAnn

fun foo(list: MutableList<Int>) {
    @JavaAnn <!WRONG_ANNOTATION_TARGET!>@KotlinAnn<!>
    when { else -> {} }

    @JavaAnn @KotlinAnn
    while (true) { break }

    @JavaAnn <!WRONG_ANNOTATION_TARGET!>@KotlinAnn<!>
    if (true) {}

    var x = 1

    @JavaAnn <!WRONG_ANNOTATION_TARGET!>@KotlinAnn<!>
    x += 2

    @JavaAnn <!WRONG_ANNOTATION_TARGET!>@KotlinAnn<!>
    list += 2
}
