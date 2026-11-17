package ru.maximserver.bench;

/**
 * Shared idle period configuration for the benchmark and its profiler.
 * <p>
 * After every measurement iteration the load is stopped for a while, so the chart shows how
 * active requests finish and threads are released. The final pause happens in
 * {@code ChatBenchmark#tearDown()} before the benchmark application is stopped; the flag makes
 * sure the profiler does not repeat the pause after the trial teardown.
 */
public final class BenchmarkIdle {

	private static final long DEFAULT_MILLIS = 10_000;

	private static final String MILLIS_ENV = "BENCH_IDLE_MILLIS";

	private static final String COMPLETED_PROPERTY = "bench.idle.completed";

	private BenchmarkIdle() {
	}

	public static long millis() {
		String value = System.getenv(MILLIS_ENV);
		if (value == null || value.isBlank()) {
			return DEFAULT_MILLIS;
		}
		try {
			return Math.max(0, Long.parseLong(value.trim()));
		}
		catch (NumberFormatException ex) {
			System.err.println("[bench-idle] invalid " + MILLIS_ENV + "='" + value + "', using " + DEFAULT_MILLIS);
			return DEFAULT_MILLIS;
		}
	}

	public static void pause() {
		long millis = millis();
		if (millis <= 0) {
			return;
		}
		System.out.println("[bench-idle] load stopped, waiting " + millis + " ms");
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	public static boolean isCompleted() {
		return Boolean.getBoolean(COMPLETED_PROPERTY);
	}

	public static void markCompleted() {
		System.setProperty(COMPLETED_PROPERTY, Boolean.TRUE.toString());
	}

}
