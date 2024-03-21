// JVM_ABI_K1_K2_DIFF: KT-65038
class GameError(msg: String): Exception(msg) {
}

fun box(): String {
  val e = GameError("foo")
  return if (e.message == "foo") "OK" else "fail"
}
