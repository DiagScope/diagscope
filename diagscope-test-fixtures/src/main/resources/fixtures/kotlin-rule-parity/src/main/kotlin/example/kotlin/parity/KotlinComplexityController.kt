package example.kotlin.parity

/**
 * Fixture: HIGH_METHOD_COMPLEXITY. The controller function below has cyclomatic complexity 19 (one path,
 * seventeen `when` branches and one short-circuit operator); the second one stays under the threshold.
 */
@RestController
open class KotlinComplexityController {

    /** HIGH_METHOD_COMPLEXITY: cyclomatic complexity above 15. */
    @GetMapping("/complexity/classify")
    open fun classify(code: Int, strict: Boolean): String {
        if (strict && code > 100) return "overflow"
        return when (code) {
            1 -> "c1"
            2 -> "c2"
            3 -> "c3"
            4 -> "c4"
            5 -> "c5"
            6 -> "c6"
            7 -> "c7"
            8 -> "c8"
            9 -> "c9"
            10 -> "c10"
            11 -> "c11"
            12 -> "c12"
            13 -> "c13"
            14 -> "c14"
            15 -> "c15"
            16 -> "c16"
            17 -> "c17"
            else -> "other"
        }
    }

    /** Safe: a handful of branches is well under the threshold. */
    @GetMapping("/complexity/simple")
    open fun classifySimple(code: Int): String {
        if (code < 0) return "negative"
        return if (code == 0) "zero" else "positive"
    }
}
