// !DIAGNOSTICS: -UNUSED_PARAMETER
// !LANGUAGE: +ValueClasses

@JvmInline
value class Z(val x: Int)

@JvmOverloads
fun testTopLevelFunction1(z: Z, x: Int = 0) {}

@JvmOverloads
fun testTopLevelFunction2(x: Int, z: Z = Z(0)) {}

@JvmOverloads
fun testTopLevelFunction3(x: Int = 0): Z = Z(x)

class C {
    @JvmOverloads
    constructor(i: Int, z: Z = Z(0))

    @JvmOverloads
    constructor(s: String, z: Z, i: Int = 0)

    @JvmOverloads
    fun testMemberFunction1(z: Z, x: Int = 0) {}

    @JvmOverloads
    fun testMemberFunction2(x: Int, z: Z = Z(0)) {}

    @JvmOverloads
    fun testMemberFunction3(x: Int = 0): Z = Z(x)
}


@JvmInline
value class ZZ(val x: Int, val y: Int)

@JvmOverloads
fun testTopLevelFunction1(ZZ: ZZ, x: Int = 0) {}

@JvmOverloads
fun testTopLevelFunction2(x: Int, ZZ: ZZ = ZZ(0, 1)) {}

@JvmOverloads
fun testTopLevelFunction3ZZ(x: Int = 0): ZZ = ZZ(x, x + 1)

class C1 {
    @JvmOverloads
    constructor(i: Int, ZZ: ZZ = ZZ(0, 1))

    @JvmOverloads
    constructor(s: String, ZZ: ZZ, i: Int = 0)

    @JvmOverloads
    fun testMemberFunction1(ZZ: ZZ, x: Int = 0) {}

    @JvmOverloads
    fun testMemberFunction2(x: Int, ZZ: ZZ = ZZ(0, 1)) {}

    @JvmOverloads
    fun testMemberFunction3(x: Int = 0): ZZ = ZZ(x, x + 1)
}