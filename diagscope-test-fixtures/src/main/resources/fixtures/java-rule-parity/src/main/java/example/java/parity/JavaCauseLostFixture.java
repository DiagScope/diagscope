package example.java.parity;

/** Fixture: CAUSE_LOST_ON_RETHROW, with the variants that must stay silent. */
@RestController
class JavaCauseLostController {

    /** CAUSE_LOST_ON_RETHROW: nothing of the original survives. */
    @GetMapping("/cause/lost")
    String lost() {
        try {
            return load();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not load the settlement file");
        }
    }

    /** CAUSE_LOST_ON_RETHROW: the text is kept, the stack trace is not. */
    @GetMapping("/cause/message-only")
    String messageOnly() {
        try {
            return load();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("load failed: " + e.getMessage());
        }
    }

    /** Safe: the cause is passed to the constructor. */
    @GetMapping("/cause/chained")
    String chained() {
        try {
            return load();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not load the settlement file", e);
        }
    }

    /** Safe: the cause is attached with initCause. */
    @GetMapping("/cause/init-cause")
    String initCause() {
        try {
            return load();
        } catch (java.io.IOException e) {
            IllegalStateException wrapped = new IllegalStateException("could not load the settlement file");
            wrapped.initCause(e);
            throw new IllegalStateException(wrapped);
        }
    }

    /** Safe: invalid input becomes a client error; the number that failed to parse is the whole story. */
    @GetMapping("/cause/parse")
    int parse(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("id must be a number");
        }
    }

    private String load() throws java.io.IOException {
        return "";
    }
}
