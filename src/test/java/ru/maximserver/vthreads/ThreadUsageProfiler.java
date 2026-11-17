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

public final class ThreadUsageProfiler implements InternalProfiler {

	private static final long DEFAULT_INTERVAL_MILLIS = 50;

	private static final String APP_URL_PROPERTY = "bench.app.url";

	private static final boolean EXTERNAL_APP = !"inprocess"
		.equalsIgnoreCase(System.getenv().getOrDefault("BENCH_APP_MODE", "external"));

	private final long intervalMillis;

	private ThreadUsageRecorder recorder;

	private HttpClient httpClient;

	public ThreadUsageProfiler(String initLine) throws ProfilerException {
		this.intervalMillis = parseInterval(initLine);
	}

	@Override
	public String getDescription() {
		return "Samples platform and virtual thread usage and writes CSV plus an SVG chart";
	}

	@Override
	public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
		String appUrl = System.getProperty(APP_URL_PROPERTY);
		if (EXTERNAL_APP) {
			if (appUrl != null) {
				post(appUrl + "/__bench/iteration/start?type=" + iterationParams.getType() + "&index="
						+ iterationParams.getCount());
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
		this.recorder.startIteration(iterationParams.getType() == IterationType.WARMUP, iterationParams.getCount());
	}

	@Override
	public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams,
			IterationParams iterationParams, IterationResult iterationResult) {
		String appUrl = System.getProperty(APP_URL_PROPERTY);
		if (EXTERNAL_APP) {
			if (appUrl != null) {
				post(appUrl + "/__bench/iteration/end?type=" + iterationParams.getType() + "&index="
						+ iterationParams.getCount());
			}
			return Collections.emptyList();
		}
		if (this.recorder != null) {
			this.recorder.endIteration(iterationParams.getType() == IterationType.WARMUP,
					iterationParams.getCount());
		}
		return Collections.emptyList();
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
