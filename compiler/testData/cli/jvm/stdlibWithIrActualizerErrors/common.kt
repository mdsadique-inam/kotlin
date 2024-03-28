// ISSUE: KT-65841

package kotlin

internal annotation class ActualizeByJvmBuiltinProvider()

@ActualizeByJvmBuiltinProvider
expect class Any() {
    fun invalid()
}
