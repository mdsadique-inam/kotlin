// FIR_IDENTICAL
// ISSUE: KT-57192
@file:OptIn(ExperimentalJsExport::class)
import kotlin.js.Promise

@JsExport
fun fooInt(p: Promise<Int>): Promise<Int>? = p

<!NON_EXPORTABLE_TYPE!>@JsExport
fun fooUnitReturn(): Promise<Unit>?<!> = null

@JsExport
fun fooUnitArgument(<!NON_EXPORTABLE_TYPE!>p: Promise<Unit><!>) {
    p.then {}
}
