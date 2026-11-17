package ru.maximserver.vthreads.chat;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts chat requests that are currently being processed by the application.
 * <p>
 * The counter is intentionally static: the thread usage sampler
 * ({@code ThreadUsageRecorder}) runs outside the Spring context and reads the value from
 * the same JVM. Once all requests are finished the counter drops back to zero.
 */
public final class ActiveRequests {

	private static final AtomicInteger ACTIVE = new AtomicInteger();

	private ActiveRequests() {
	}

	public static void increment() {
		ACTIVE.incrementAndGet();
	}

	public static void decrement() {
		ACTIVE.decrementAndGet();
	}

	public static int current() {
		return ACTIVE.get();
	}

}
