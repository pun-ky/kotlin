// IGNORE_BACKEND_FIR: JVM_IR
// ISSUE: KT-41176
// WITH_RUNTIME

fun <R> materialize(): R = "hello" as R
fun <T> id(x: T): T = x
fun <K> select(x: K, y: K): K = x

fun box(): String {
    check(ifProblem(true))
    check(whenProblem(true))
    check(tryProblem())

    return "OK"
}

fun check(s: String?) {
    if (s != "hello") {
        throw IllegalArgumentException()
    }
}

fun ifProblem(b: Boolean): String? {
    return run {
        if (b) { materialize() } else null
    }
}

fun whenProblem(b: Boolean): String? {
    return run {
        when {
            b -> materialize()
            else -> null
        }
    }
}

fun tryProblem(): String? {
    return run {
        try {
            materialize()
        } catch (e: Exception) {
            null
        }
    }
}
