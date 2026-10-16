package example.java.parity;

/** Test-source fixture for the TEST_* rules; scanned only with --include-tests. */
class JavaTestRulesFixture {
    private final JavaAuditClient client = new JavaAuditClient();

    // TRIGGERS: TEST_WITHOUT_ASSERTION — runs code, checks nothing
    @Test
    void runsWithoutChecking() {
        client.record("created");
    }

    // SAFE: asserts the outcome
    @Test
    void checksTheOutcome() {
        client.record("created");
        assertEquals(1, 1);
    }

    // SAFE: the assertion lives in a helper the test calls
    @Test
    void checksThroughAHelper() {
        client.record("created");
        verifyRecorded();
    }

    void verifyRecorded() {
        assertTrue(true);
    }

    // TRIGGERS: TEST_SWALLOWS_EXCEPTION — a broad catch turns a failure into a pass
    @Test
    void swallowsTheFailure() {
        try {
            client.record("created");
        } catch (Exception ignored) {
            // nothing
        }
    }

    // SAFE: catches the specific exception and asserts on it
    @Test
    void assertsOnTheSpecificFailure() {
        try {
            client.record(null);
        } catch (IllegalArgumentException expected) {
            assertEquals("event", expected.getMessage());
        }
    }

    // TRIGGERS: TEST_DISABLED_WITHOUT_REASON
    @Disabled
    @Test
    void parked() {
        client.record("created");
    }

    // SAFE: says why
    @Disabled("PAY-123 waiting for the sandbox")
    @Test
    void parkedWithReason() {
        client.record("created");
    }

    // TRIGGERS: TEST_THREAD_SLEEP
    @Test
    void waitsWithASleep() throws Exception {
        client.record("created");
        Thread.sleep(500);
        assertEquals(1, 1);
    }

    // TRIGGERS: TEST_BROAD_EXPECTED_EXCEPTION
    @Test
    void expectsAnything() {
        assertThrows(Exception.class, () -> client.record(null));
    }

    // SAFE: expects the specific exception
    @Test
    void expectsTheSpecificException() {
        assertThrows(IllegalArgumentException.class, () -> client.record(null));
    }
}
