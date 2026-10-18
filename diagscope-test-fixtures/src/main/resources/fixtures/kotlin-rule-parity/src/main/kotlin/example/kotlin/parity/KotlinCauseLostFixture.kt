package example.kotlin.parity

/** Fixture: CAUSE_LOST_ON_RETHROW, with the variants that must stay silent. */
@RestController
open class KotlinCauseLostController {

    /** CAUSE_LOST_ON_RETHROW: nothing of the original survives. */
    @GetMapping("/cause/lost")
    open fun lost(): String {
        try {
            return load()
        } catch (e: java.io.IOException) {
            throw IllegalStateException("could not load the settlement file")
        }
    }

    /** CAUSE_LOST_ON_RETHROW: the text is kept, the stack trace is not. */
    @GetMapping("/cause/message-only")
    open fun messageOnly(): String {
        try {
            return load()
        } catch (e: java.io.IOException) {
            throw IllegalStateException("load failed: " + e.message)
        }
    }

    /** Safe: the cause is passed to the constructor. */
    @GetMapping("/cause/chained")
    open fun chained(): String {
        try {
            return load()
        } catch (e: java.io.IOException) {
            throw IllegalStateException("could not load the settlement file", e)
        }
    }

    /** Safe: invalid input becomes a client error. */
    @GetMapping("/cause/parse")
    open fun parse(id: String): Int {
        try {
            return Integer.parseInt(id)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("id must be a number")
        }
    }

    private fun load(): String = ""
}
