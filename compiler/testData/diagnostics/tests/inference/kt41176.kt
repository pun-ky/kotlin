// !DIAGNOSTICS: -UNUSED_PARAMETER
// ISSUE: KT-41176
// WITH_RUNTIME

fun <R> materialize(): R = null!!
fun <T> id(x: T): T = x
fun <K> select(x: K, y: K): K = x

fun ifProblem(b: Boolean): String? {
    return <!DEBUG_INFO_EXPRESSION_TYPE("kotlin.String?")!>run {
        <!DEBUG_INFO_EXPRESSION_TYPE("kotlin.Nothing?")!>id(if (b) { materialize() } else null)<!>
        if (b) {
            return@run if (b) { materialize() } else null
        }

        <!DEBUG_INFO_EXPRESSION_TYPE("kotlin.String?")!>if (b) { materialize() } else null<!>
    }<!>
}

fun whenProblem(b: Boolean): String? {
    return run {
        <!DEBUG_INFO_EXPRESSION_TYPE("kotlin.String?")!>when {
            b -> materialize()
            else -> null
        }<!>
    }
}

fun tryProblem(): String? {
    return run {
        <!DEBUG_INFO_EXPRESSION_TYPE("kotlin.String?")!>try {
            materialize()
        } catch (e: Exception) {
            null
        }<!>
    }
}

fun selectProblem(): String? {
    return run {
        <!DEBUG_INFO_EXPRESSION_TYPE("kotlin.Nothing?")!>select(<!IMPLICIT_NOTHING_TYPE_ARGUMENT_AGAINST_NOT_NOTHING_EXPECTED_TYPE!>materialize<!>(), null)<!>
    }
}
