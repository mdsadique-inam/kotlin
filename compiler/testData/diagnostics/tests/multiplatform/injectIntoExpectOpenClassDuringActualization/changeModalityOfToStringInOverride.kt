// FIR_IDENTICAL
// MODULE: m1-common
// FILE: common.kt

expect open class Foo

// MODULE: m2-jvm()()(m1-common)
// FILE: jvm.kt

actual open <!NON_FINAL_EXPECT_CLASSIFIER_MUST_HAVE_THE_SAME_MEMBERS_AS_ACTUAL_CLASSIFIER!>class Foo<!> {
    <!MODALITY_OVERRIDE_IN_NON_FINAL_EXPECT_CLASSIFIER_ACTUALIZATION!>final<!> override fun toString() = "Foo"
}
