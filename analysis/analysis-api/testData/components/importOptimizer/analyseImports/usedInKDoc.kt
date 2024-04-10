// FILE: main.kt
package test

import dependency.B
import dependency.C
import dependency.bar

/**
 * [dependency.A]
 * [dependency.C]
 * [B]
 * [bar]
 */
fun foo() {

}
// FILE: dependency.kt
package dependency

class A
class B
fun bar()