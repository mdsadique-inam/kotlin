// WITH_STDLIB
// LANGUAGE: +DataClassCopyRespectsConstructorVisibility
@kotlin.ConsistentDataCopyVisibility
class Foo

@kotlin.InconsistentDataCopyVisibility
class Bar

@kotlin.ConsistentDataCopyVisibility
data class Data(val x: Int)
