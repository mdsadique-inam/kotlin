// JVM_ABI_K1_K2_DIFF: KT-65038


sealed class Result {
    class Failure(val exception: Exception) : Result()
    class Success(val message: String) : Result()
}

fun box(): String {
    var result: Result
    try {
        result = Result.Success("OK")
    }
    catch (e: Exception) {
        result = Result.Failure(Exception())
    }

    when (result) {
        is Result.Failure -> throw result.exception
        is Result.Success -> return result.message
    }
}