// FILE: main.kt
package test

import dependency.B
import dependency.C
import dependency.bar
import dependency.Foo
import dependency.zoo
import dependency.Bar
import dependency.b
import dependency.Ban
import dependency.Fun
import dependency.ext

/**
 * [dependency.A]
 * [dependency.C]
 * [B]
 * [bar]
 * [zoo]
 * [Bar.b]
 * [Ban.ext]
 */
fun foo() {

}
// FILE: dependency.kt
package dependency

class A
class B
fun bar() {}
class Foo
fun Foo.zoo() {}
class Bar
fun Bar.b(){}

open class Fun
class Ban : Fun()
fun Fun.ext() {}