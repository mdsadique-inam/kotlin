// FILE: main.kt
package test

import dependency.B
import dependency.C
import dependency.bar
import dependency.Foo
import dependency.zoo

/**
 * [dependency.A]
 * [dependency.C]
 * [B]
 * [bar]
 * [zoo]
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