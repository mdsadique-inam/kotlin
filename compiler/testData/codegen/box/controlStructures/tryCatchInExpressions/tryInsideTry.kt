// JVM_ABI_K1_K2_DIFF: KT-65038

class MyException(message: String): Exception(message)

fun box(): String =
        "O" +
        try {
            try { throw Exception("oops!") } catch (mye: MyException) { "1" }
        }
        catch (e: Exception) {
            "K"
        }