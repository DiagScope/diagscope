package dev.diagscope.core.application.rule;

/**
 * The kind of thread a method body is expected to run on, as far as source evidence can prove it.
 *
 * <p>The context decides which operations are dangerous: blocking is a defect on an
 * {@link #EVENT_LOOP} or a {@link #COROUTINE} dispatcher thread, and is the expected behaviour on a
 * {@link #WORKER} or {@link #VIRTUAL_THREAD}.</p>
 */
public enum ExecutionContext {
    /** A shared non-blocking I/O thread (Vert.x/Netty event loop, Reactor, Mutiny). Never block here. */
    EVENT_LOOP(true),
    /** A kotlinx.coroutines dispatcher thread. Blocking starves every other coroutine on it. */
    COROUTINE(true),
    /** A pooled platform thread that exists to run blocking work. */
    WORKER(false),
    /** A virtual thread; blocking parks it cheaply. */
    VIRTUAL_THREAD(false);

    private final boolean blockingForbidden;

    ExecutionContext(boolean blockingForbidden) {
        this.blockingForbidden = blockingForbidden;
    }

    /** Whether a blocking call in this context is a defect. */
    public boolean blockingForbidden() {
        return blockingForbidden;
    }
}
