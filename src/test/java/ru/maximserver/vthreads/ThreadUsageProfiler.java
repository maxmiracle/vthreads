package ru.maximserver.vthreads;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.profile.ProfilerException;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.runner.IterationType;

import ru.maximserver.bench.BenchmarkIdle;

public final class ThreadUsageProfiler implements InternalProfiler {

	private static final long DEFAULT_INTERVAL_MILLIS = 50;

	private static final String APP_URL_PROPERTY = "bench.app.url";

	private static final boolean EXTERNAL_APP = !"inprocess"
		.equalsIgnoreCase(System.getenv().getOrDefault("BENCH_APP_MODE", "external"));

	private static volatile ThreadUsageProfiler activeProfiler;

	private final long intervalMillis;

	private ThreadUsageRecorder recorder;

	private HttpClient httpClient;

	private int warmupIterations;

	private int measurementIterations;

	private int currentIterationIndex;

	private IterationType pendingStartType;

	private int pendingStartIndex;

	public ThreadUsageProfiler(String initLine) throws ProfilerException {
		this.intervalMillis = parseInterval(initLine);
		activeProfiler = this;
	}

	@Override
	public String getDescription() {
		return "Samples platform and virtual thread usage, active requests and writes CSV plus an SVG chart";
	}

	@Override
	public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
		this.currentIterationIndex = nextIterationIndex(iterationParams.getType());
		String appUrl = System.getProperty(APP_URL_PROPERTY);
		if (EXTERNAL_APP) {
			if (appUrl != null) {
				post(appUrl + "/__bench/iteration/start?type=" + iterationParams.getType() + "&index="
						+ this.currentIterationIndex);
			}
			else {
				// The external application is still starting up during the very first iteration;
				// ChatBenchmark reports the start once the URL is known.
				this.pendingStartType = iterationParams.getType();
				this.pendingStartIndex = this.currentIterationIndex;
			}
			return;
		}
		if (appUrl != null) {
			return;
		}
		if (this.recorder == null) {
			this.recorder = new ThreadUsageRecorder(buildFilePrefix(benchmarkParams, System.currentTimeMillis()),
					this.intervalMillis);
			this.recorder.start();
		}
		this.recorder.startIteration(iterationParams.getType() == IterationType.WARMUP, this.currentIterationIndex);
	}

	@Override
	public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams,
			IterationParams iterationParams, IterationResult iterationResult) {
		String appUrl = System.getProperty(APP_URL_PROPERTY);
		if (EXTERNAL_APP) {
			if (appUrl != null) {
				post(appUrl + "/__bench/iteration/end?type=" + iterationParams.getType() + "&index="
						+ this.currentIterationIndex);
			}
		}
		else if (this.recorder != null) {
			this.recorder.endIteration(iterationParams.getType() == IterationType.WARMUP,
					this.currentIterationIndex);
		}
		pauseAfterMeasurement(iterationParams);
		return Collections.emptyList();
	}

	/**
	 * Stops the load after every measurement iteration, so the chart shows how threads are
	 * released once the requests are finished. The pause happens after the iteration is closed
	 * and does not affect the JMH score. The final pause is performed by
	 * {@code ChatBenchmark#tearDown()} before the application is stopped, hence the flag.
	 */
	private void pauseAfterMeasurement(IterationParams iterationParams) {
		if (iterationParams.getType() == IterationType.WARMUP || BenchmarkIdle.isCompleted()) {
			return;
		}
		BenchmarkIdle.pause();
	}

	private int nextIterationIndex(IterationType type) {
		if (type == IterationType.WARMUP) {
			return this.warmupIterations++;
		}
		return this.measurementIterations++;
	}

	/**
	 * Reports the iteration that started before the external application was ready.
	 * Called by the benchmark at the beginning of the first iteration.
	 */
	public static void startPendingIteration(String appUrl) {
		ThreadUsageProfiler profiler = activeProfiler;
		if (profiler == null || profiler.pendingStartType == null || appUrl == null) {
			return;
		}
		profiler.post(appUrl + "/__bench/iteration/start?type=" + profiler.pendingStartType + "&index="
				+ profiler.pendingStartIndex);
		profiler.pendingStartType = null;
	}

	private void post(String url) {
		try {
			if (this.httpClient == null) {
				this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			}
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(5))
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
			this.httpClient.send(request, HttpResponse.BodyHandlers.discarding());
		}
		catch (Exception ex) {
			System.err.println("[thread-usage] failed to call benchmark app: " + ex);
		}
	}

	private static String buildFilePrefix(BenchmarkParams benchmarkParams, long wallMillis) {
		String benchmark = benchmarkParams.getBenchmark();
		String simpleName = benchmark.substring(benchmark.lastIndexOf('.') + 1);
		StringBuilder name = new StringBuilder(sanitize(simpleName));
		benchmarkParams.getParamsKeys()
			.forEach(key -> name.append('-')
				.append(sanitize(key))
				.append('-')
				.append(sanitize(benchmarkParams.getParam(key))));
		return "target/jmh-thread-usage-" + name + "-" + wallMillis;
	}

	private static String sanitize(String value) {
		return value.replaceAll("[^A-Za-z0-9]+", "-");
	}

	private static long parseInterval(String initLine) throws ProfilerException {
		if (initLine == null || initLine.isBlank()) {
			return DEFAULT_INTERVAL_MILLIS;
		}
		for (String option : initLine.split(";")) {
			String trimmed = option.trim();
			if (trimmed.startsWith("interval=")) {
				try {
					return Math.max(1, Long.parseLong(trimmed.substring("interval=".length())));
				}
				catch (NumberFormatException ex) {
					throw new ProfilerException("Invalid interval: " + trimmed, ex);
				}
			}
		}
		return DEFAULT_INTERVAL_MILLIS;
	}

}
