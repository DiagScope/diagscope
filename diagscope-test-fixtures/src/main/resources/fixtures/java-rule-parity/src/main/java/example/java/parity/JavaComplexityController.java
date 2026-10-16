package example.java.parity;

/**
 * Fixture: HIGH_METHOD_COMPLEXITY. The controller method below has cyclomatic complexity 18 (one path,
 * sixteen {@code if} branches and one short-circuit operator); the second one stays under the threshold.
 */
@RestController
class JavaComplexityController {

    /** HIGH_METHOD_COMPLEXITY: cyclomatic complexity above 15. */
    @GetMapping("/complexity/classify")
    String classify(int code, boolean strict) {
        if (code == 1) {
            return "c1";
        }
        if (code == 2) {
            return "c2";
        }
        if (code == 3) {
            return "c3";
        }
        if (code == 4) {
            return "c4";
        }
        if (code == 5) {
            return "c5";
        }
        if (code == 6) {
            return "c6";
        }
        if (code == 7) {
            return "c7";
        }
        if (code == 8) {
            return "c8";
        }
        if (code == 9) {
            return "c9";
        }
        if (code == 10) {
            return "c10";
        }
        if (code == 11) {
            return "c11";
        }
        if (code == 12) {
            return "c12";
        }
        if (code == 13) {
            return "c13";
        }
        if (code == 14) {
            return "c14";
        }
        if (code == 15) {
            return "c15";
        }
        if (code == 16) {
            return "c16";
        }
        if (strict && code > 100) {
            return "overflow";
        }
        return "other";
    }

    /** Safe: a handful of branches is well under the threshold. */
    @GetMapping("/complexity/simple")
    String classifySimple(int code) {
        if (code < 0) {
            return "negative";
        }
        return code == 0 ? "zero" : "positive";
    }
}
