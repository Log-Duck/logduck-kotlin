package com.logduck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The README calls this a "Kotlin and Java SDK", and this is what makes that
 * claim testable.
 *
 * Kotlin default arguments generate no overloads for Java. Without
 * {@code @JvmOverloads} on the constructors, every line below fails to compile
 * — a Java caller would have to pass all seven arguments to {@code
 * LogDuckOptions} just to set the two that are required. That breakage is
 * invisible from Kotlin, so only a test written in Java can catch it.
 *
 * This test exists to fail at compile time. The assertions are almost
 * incidental.
 */
class JavaInteropTest {

    @Test
    void constructsWithOnlyTheRequiredArguments() {
        LogDuckOptions options = new LogDuckOptions("ld_test_key", "java-app");

        assertEquals("ld_test_key", options.getApiKey());
        assertEquals("java-app", options.getSource());
        // The Kotlin-side defaults must survive the Java constructor.
        assertFalse(options.getThrowOnError());
        assertEquals(Duration.ofSeconds(10), options.getMaxRetryDelay());
        assertEquals("https://api.logduck.com", options.getBaseUrl());
    }

    @Test
    void constructsAnEventWithOnlyATypeAndAClientWithOnlyOptions() {
        LogDuckEvent event = new LogDuckEvent("order.placed");
        LogDuckClient client = new LogDuckClient(new LogDuckOptions("ld_test_key", "java-app"));

        assertEquals("order.placed", event.getType());
        assertNotNull(client);
    }

    @Test
    void sendBlockingIsReachableWithoutACoroutineScope() {
        // Java has no way to call a suspend function, so sendBlocking is the
        // whole Java entry point. Point it at a closed port: the client must
        // report the failure rather than throw, which is also the documented
        // default behaviour.
        LogDuckClient client = new LogDuckClient(
            new LogDuckOptions(
                "ld_test_key",
                "java-app",
                false,
                false,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                "http://127.0.0.1:1"
            ),
            request -> { throw new RuntimeException("no network in tests"); },
            (message, error) -> { }
        );

        assertEquals(null, client.sendBlocking(new LogDuckEvent("order.placed")));
    }
}
