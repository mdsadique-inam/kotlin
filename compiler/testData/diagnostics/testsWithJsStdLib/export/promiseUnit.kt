// FIR_IDENTICAL
// ISSUE: KT-57192
// WITH_COROUTINES
@file:OptIn(ExperimentalJsExport::class)
import kotlin.js.Promise

@JsExport
fun fooInt(p: Promise<Int>): Promise<Int> = p

<!NON_EXPORTABLE_TYPE!>@JsExport
fun fooUnit(<!NON_EXPORTABLE_TYPE!>p: Promise<Unit><!>): Promise<Unit><!> = p

