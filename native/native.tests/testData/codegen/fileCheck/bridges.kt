// TARGET_BACKEND: NATIVE
// FILECHECK_STAGE: CStubs

interface I1 {
    fun foo(): Int
}

class C1 : I1 {
    override fun foo(): Nothing = TODO()
}
// CHECK-LABEL: define i32 @"kfun:C1#$<bridge-DNN>foo(){}kotlin.Nothing(){}kotlin.Int
// CHECK-LABEL: epilogue:

open class Foo2<T>(val x: T)

interface I2 {
    val x: Unit
}

class Bar2 : Foo2<Unit>(Unit), I2
// CHECK-LABEL: define void @"kfun:Bar2#$<bridge-DNN><get-x>(){}(){}
// CHECK-LABEL: epilogue:

fun getX2(i: I2) = i.x

// CHECK-LABEL: define %struct.ObjHeader* @"kfun:#box(){}kotlin.String"
fun box(): String {
    val i1: I1 = C1()
    try {
        println(i1.foo())
    } catch (t: Throwable) { }

    val i2: I2 = Bar2()
    getX2(i2)

    return "OK"
}
