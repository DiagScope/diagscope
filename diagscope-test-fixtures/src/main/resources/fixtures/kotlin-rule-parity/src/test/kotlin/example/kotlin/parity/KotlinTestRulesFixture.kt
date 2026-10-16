package example.kotlin.parity

/** Test-source fixture for the TEST_* rules; scanned only with --include-tests. */
class KotlinTestRulesFixture {
    private val client = KotlinAuditClient()

    // TRIGGERS: TEST_WITHOUT_ASSERTION — runs code, checks nothing
    @Test
    fun runsWithoutChecking() {
        client.record("created")
    }

    // SAFE: asserts the outcome
    @Test
    fun checksTheOutcome() {
        client.record("created")
        assertEquals(1, 1)
    }

    // SAFE: the assertion lives in a helper the test calls
    @Test
    fun checksThroughAHelper() {
        client.record("created")
        verifyRecorded()
    }

    fun verifyRecorded() {
        assertTrue(true)
    }

    // TRIGGERS: TEST_SWALLOWS_EXCEPTION — a broad catch turns a failure into a pass
    @Test
    fun swallowsTheFailure() {
        try {
            client.record("created")
        } catch (ignored: Exception) {
            // nothing
        }
    }

    // SAFE: catches the specific exception and asserts on it
    @Test
    fun assertsOnTheSpecificFailure() {
        try {
            client.record("")
        } catch (expected: IllegalArgumentException) {
            assertEquals("event", expected.message)
        }
    }

    // TRIGGERS: TEST_DISABLED_WITHOUT_REASON
    @Disabled
    @Test
    fun parked() {
        client.record("created")
    }

    // SAFE: says why
    @Disabled("PAY-123 waiting for the sandbox")
    @Test
    fun parkedWithReason() {
        client.record("created")
    }

    // TRIGGERS: TEST_THREAD_SLEEP
    @Test
    fun waitsWithASleep() {
        client.record("created")
        Thread.sleep(500)
        assertEquals(1, 1)
    }

    // TRIGGERS: TEST_BROAD_EXPECTED_EXCEPTION
    @Test
    fun expectsAnything() {
        assertThrows(Exception::class.java) { client.record("") }
    }

    // SAFE: expects the specific exception
    @Test
    fun expectsTheSpecificException() {
        assertThrows(IllegalArgumentException::class.java) { client.record("") }
    }
}
