// TARGET_BACKEND: WASM

// RUN_THIRD_PARTY_OPTIMIZER
// WASM_DCE_EXPECTED_OUTPUT_SIZE: wasm  35_681
// WASM_DCE_EXPECTED_OUTPUT_SIZE: mjs    5_431
// WASM_OPT_EXPECTED_OUTPUT_SIZE:        8_709

fun box(): String {
    println("Hello, World!")
    return "OK"
}
