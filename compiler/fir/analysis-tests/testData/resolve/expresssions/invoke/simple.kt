// RUN_PIPELINE_TILL: BACKEND
class Simple {
    operator fun invoke(): String = "invoke"
}

fun test(s: Simple) {
    val result = s()
}
