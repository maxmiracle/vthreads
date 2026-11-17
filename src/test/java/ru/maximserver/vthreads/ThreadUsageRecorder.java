package ru.maximserver.vthreads;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jdk.management.VirtualThreadSchedulerMXBean;
import ru.maximserver.vthreads.chat.ActiveRequests;

public final class ThreadUsageRecorder {

	private static final long DEFAULT_INTERVAL_MILLIS = 50;

	private static volatile ThreadUsageRecorder activeRecorder;

	private final String filePrefix;

	private final long intervalMillis;

	private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

	private final VirtualThreadSchedulerMXBean virtualThreadSchedulerMXBean = ManagementFactory
		.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);

	private final Object lock = new Object();

	private final Object lifecycleLock = new Object();

	private final List<Sample> samples = new ArrayList<>();

	private final List<Iteration> iterations = new ArrayList<>();

	private Thread sampler;

	private boolean running;

	private boolean started;

	private boolean stopped;

	private long firstSampleNanos;

	private long iterationStartNanos;

	private boolean iterationWarmup;

	private int iterationIndex;

	private boolean iterationOpen;

	public ThreadUsageRecorder(String filePrefix) {
		this(filePrefix, DEFAULT_INTERVAL_MILLIS);
	}

	public ThreadUsageRecorder(String filePrefix, long intervalMillis) {
		this.filePrefix = filePrefix;
		this.intervalMillis = Math.max(1, intervalMillis);
	}

	public void start() {
		synchronized (this.lifecycleLock) {
			if (this.started) {
				return;
			}
			this.started = true;
			this.running = true;
			this.firstSampleNanos = System.nanoTime();
			this.sampler = new Thread(this::sampleLoop, "thread-usage-sampler");
			this.sampler.setDaemon(true);
			this.sampler.start();
			activeRecorder = this;
			Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "thread-usage-writer"));
		}
	}

	public void startIteration(boolean warmup, int index) {
		synchronized (this.lock) {
			if (this.iterationOpen) {
				closeIteration();
			}
			this.iterationStartNanos = System.nanoTime();
			this.iterationWarmup = warmup;
			this.iterationIndex = index;
			this.iterationOpen = true;
		}
	}

	public void endIteration(boolean warmup, int index) {
		synchronized (this.lock) {
			if (!this.started || this.samples.isEmpty()) {
				return;
			}
			if (this.iterationOpen) {
				closeIteration();
			}
			else if (this.iterations.isEmpty()) {
				// The profiler could not report the start of the very first iteration because the
				// external application was still booting; use the recorder start as the band start.
				this.iterations.add(new Iteration(warmup, index, this.firstSampleNanos, System.nanoTime()));
			}
			writeOutputs();
		}
	}

	/**
	 * Closes the iteration that is currently open in the recorder of this JVM, if any.
	 * <p>
	 * JMH runs {@code @TearDown(Level.Trial)} inside the last iteration, before the profiler's
	 * {@code afterIteration}. The in-process benchmark uses this method to end the last
	 * measurement iteration before the idle period, so the release is drawn outside the band.
	 * @return {@code true} if an open iteration was closed
	 */
	public static boolean endCurrentIteration() {
		ThreadUsageRecorder recorder = activeRecorder;
		if (recorder == null) {
			return false;
		}
		synchronized (recorder.lock) {
			if (!recorder.iterationOpen) {
				return false;
			}
			recorder.closeIteration();
			return true;
		}
	}

	public void stop() {
		synchronized (this.lifecycleLock) {
			if (this.stopped) {
				return;
			}
			this.stopped = true;
			this.running = false;
			if (this.sampler != null) {
				this.sampler.interrupt();
				try {
					this.sampler.join(500);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			synchronized (this.lock) {
				if (this.started) {
					if (this.iterationOpen) {
						closeIteration();
					}
					writeOutputs();
				}
			}
			if (activeRecorder == this) {
				activeRecorder = null;
			}
		}
	}

	private void closeIteration() {
		this.iterations.add(new Iteration(this.iterationWarmup, this.iterationIndex, this.iterationStartNanos,
				System.nanoTime()));
		this.iterationOpen = false;
	}

	private void sampleLoop() {
		while (this.running) {
			long nanos = System.nanoTime();
			Sample sample = new Sample(nanos, this.threadMXBean.getThreadCount(),
					this.virtualThreadSchedulerMXBean.getMountedVirtualThreadCount(),
					this.virtualThreadSchedulerMXBean.getQueuedVirtualThreadCount(),
					this.virtualThreadSchedulerMXBean.getPoolSize(),
					this.virtualThreadSchedulerMXBean.getParallelism(), ActiveRequests.current());
			synchronized (this.lock) {
				this.samples.add(sample);
			}
			try {
				Thread.sleep(this.intervalMillis);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void writeOutputs() {
		if (this.samples.isEmpty()) {
			return;
		}
		try {
			Path csv = Path.of(this.filePrefix + ".csv");
			Path svg = Path.of(this.filePrefix + ".svg");
			Path parent = csv.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			writeCsv(csv);
			writeSvg(svg);
			System.out.println("[thread-usage] wrote " + csv.toAbsolutePath() + " and " + svg.toAbsolutePath());
		}
		catch (IOException ex) {
			System.err.println("[thread-usage] failed to write output: " + ex);
		}
	}

	private void writeCsv(Path csv) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(csv)) {
			writer.write(
					"elapsed_ms,platform_threads,mounted_virtual_threads,queued_virtual_threads,carrier_pool_size,carrier_parallelism,active_requests\n");
			for (Sample sample : this.samples) {
				writer.write(String.format(Locale.ROOT, "%.1f,%d,%d,%d,%d,%d,%d%n",
						elapsedMillis(sample.nanos()), sample.platformThreads(), sample.mountedVirtualThreads(),
						sample.queuedVirtualThreads(), sample.carrierPoolSize(), sample.carrierParallelism(),
						sample.activeRequests()));
			}
		}
	}

	private void writeSvg(Path svg) throws IOException {
		double maxX = 1;
		double maxThreads = 1;
		double maxActiveRequests = 1;
		for (Sample sample : this.samples) {
			maxX = Math.max(maxX, elapsedMillis(sample.nanos()));
			maxThreads = Math.max(maxThreads, Math.max(sample.platformThreads(),
					Math.max(sample.mountedVirtualThreads(), Math.max(sample.queuedVirtualThreads(),
							sample.carrierPoolSize()))));
			maxActiveRequests = Math.max(maxActiveRequests, sample.activeRequests());
		}
		for (Iteration iteration : this.iterations) {
			maxX = Math.max(maxX, elapsedMillis(iteration.endNanos()));
		}

		int width = 1200;
		int height = 720;
		int left = 70;
		int plotWidth = width - left - 30;
		Plot threadsPlot = new Plot(86, 284);
		Plot requestsPlot = new Plot(442, 190);
		double axisMaxThreads = niceCeiling(maxThreads);
		double axisMaxActiveRequests = niceCeiling(maxActiveRequests);

		StringBuilder svgBuilder = new StringBuilder(16_384);
		svgBuilder.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width).append("\" height=\"")
			.append(height).append("\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">\n");
		svgBuilder.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
		svgBuilder.append("<text x=\"").append(width / 2).append("\" y=\"28\" text-anchor=\"middle\" ")
			.append("font-family=\"sans-serif\" font-size=\"18\" font-weight=\"bold\">")
			.append("Thread usage and active requests</text>\n");

		for (Iteration iteration : this.iterations) {
			double x1 = x(elapsedMillis(iteration.startNanos()), maxX, left, plotWidth);
			double x2 = x(elapsedMillis(iteration.endNanos()), maxX, left, plotWidth);
			String fill = iteration.warmup() ? "#9e9e9e" : "#4c78a8";
			String opacity = iteration.warmup() ? "0.12" : "0.10";
			for (Plot plot : List.of(threadsPlot, requestsPlot)) {
				svgBuilder.append("<rect x=\"").append(fmt(x1)).append("\" y=\"").append(plot.top())
					.append("\" width=\"").append(fmt(Math.max(0, x2 - x1))).append("\" height=\"")
					.append(plot.height()).append("\" fill=\"").append(fill).append("\" fill-opacity=\"")
					.append(opacity).append("\"/>\n");
			}
			svgBuilder.append("<text x=\"").append(fmt((x1 + x2) / 2)).append("\" y=\"")
				.append(threadsPlot.top() + 14)
				.append("\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"10\" fill=\"#666\">")
				.append(iteration.warmup() ? "warmup " : "measurement ").append(iteration.index()).append("</text>\n");
		}

		appendHorizontalGrid(svgBuilder, threadsPlot, axisMaxThreads, left, plotWidth);
		appendHorizontalGrid(svgBuilder, requestsPlot, axisMaxActiveRequests, left, plotWidth);
		appendVerticalGrid(svgBuilder, threadsPlot, requestsPlot, maxX, left, plotWidth);

		int xLabelsY = requestsPlot.top() + requestsPlot.height() + 20;
		for (int i = 0; i <= 5; i++) {
			double value = maxX * i / 5;
			double x = x(value, maxX, left, plotWidth);
			svgBuilder.append("<text x=\"").append(fmt(x)).append("\" y=\"").append(xLabelsY)
				.append("\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#555\">")
				.append(fmt(value)).append("</text>\n");
		}
		svgBuilder.append("<text x=\"").append(left + plotWidth / 2).append("\" y=\"").append(height - 12)
			.append("\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#333\">")
			.append("elapsed time, ms</text>\n");
		appendRotatedLabel(svgBuilder, "threads", threadsPlot);
		appendRotatedLabel(svgBuilder, "active requests", requestsPlot);

		appendSeries(svgBuilder, "#e45756", sample -> sample.platformThreads(), maxX, axisMaxThreads, left,
				plotWidth, threadsPlot);
		appendSeries(svgBuilder, "#4c78a8", sample -> sample.mountedVirtualThreads(), maxX, axisMaxThreads, left,
				plotWidth, threadsPlot);
		appendSeries(svgBuilder, "#f58518", sample -> sample.queuedVirtualThreads(), maxX, axisMaxThreads, left,
				plotWidth, threadsPlot);
		appendSeries(svgBuilder, "#54a24b", sample -> sample.carrierPoolSize(), maxX, axisMaxThreads, left,
				plotWidth, threadsPlot);
		appendSeries(svgBuilder, "#b279a2", Sample::activeRequests, maxX, axisMaxActiveRequests, left, plotWidth,
				requestsPlot);

		int legendY = 50;
		int legendX = left;
		legendX = appendLegend(svgBuilder, legendX, legendY, "Platform threads", "#e45756");
		legendX = appendLegend(svgBuilder, legendX, legendY, "Mounted virtual threads", "#4c78a8");
		legendX = appendLegend(svgBuilder, legendX, legendY, "Queued virtual threads", "#f58518");
		legendX = appendLegend(svgBuilder, legendX, legendY, "Carrier pool size", "#54a24b");
		appendLegend(svgBuilder, legendX, legendY, "Active requests", "#b279a2");

		svgBuilder.append("</svg>\n");
		Files.writeString(svg, svgBuilder.toString());
	}

	private void appendHorizontalGrid(StringBuilder svgBuilder, Plot plot, double maxY, int left, int plotWidth) {
		for (int i = 0; i <= 5; i++) {
			double value = maxY * i / 5;
			double y = y(value, maxY, plot.top(), plot.height());
			svgBuilder.append("<line x1=\"").append(left).append("\" y1=\"").append(fmt(y)).append("\" x2=\"")
				.append(left + plotWidth).append("\" y2=\"").append(fmt(y))
				.append("\" stroke=\"#e0e0e0\" stroke-width=\"1\"/>\n");
			svgBuilder.append("<text x=\"").append(left - 10).append("\" y=\"").append(fmt(y + 4))
				.append("\" text-anchor=\"end\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#555\">")
				.append(fmt(value)).append("</text>\n");
		}
	}

	private void appendVerticalGrid(StringBuilder svgBuilder, Plot threadsPlot, Plot requestsPlot, double maxX,
			int left, int plotWidth) {
		for (int i = 0; i <= 5; i++) {
			double value = maxX * i / 5;
			double x = x(value, maxX, left, plotWidth);
			for (Plot plot : List.of(threadsPlot, requestsPlot)) {
				svgBuilder.append("<line x1=\"").append(fmt(x)).append("\" y1=\"").append(plot.top())
					.append("\" x2=\"").append(fmt(x)).append("\" y2=\"").append(plot.top() + plot.height())
					.append("\" stroke=\"#f0f0f0\" stroke-width=\"1\"/>\n");
			}
		}
	}

	private void appendRotatedLabel(StringBuilder svgBuilder, String label, Plot plot) {
		double centerY = plot.top() + plot.height() / 2.0;
		svgBuilder.append("<text x=\"18\" y=\"").append(fmt(centerY))
			.append("\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#333\" ")
			.append("transform=\"rotate(-90 18 ").append(fmt(centerY)).append(")\">").append(label).append("</text>\n");
	}

	private void appendSeries(StringBuilder svgBuilder, String color, SampleValue value, double maxX, double maxY,
			int left, int plotWidth, Plot plot) {
		svgBuilder.append("<polyline fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"2\" points=\"");
		boolean first = true;
		for (Sample sample : this.samples) {
			double x = x(elapsedMillis(sample.nanos()), maxX, left, plotWidth);
			double y = y(value.get(sample), maxY, plot.top(), plot.height());
			if (!first) {
				svgBuilder.append(' ');
			}
			svgBuilder.append(fmt(x)).append(',').append(fmt(y));
			first = false;
		}
		svgBuilder.append("\"/>\n");
	}

	private int appendLegend(StringBuilder svgBuilder, int x, int y, String label, String color) {
		svgBuilder.append("<rect x=\"").append(x).append("\" y=\"").append(y - 10)
			.append("\" width=\"12\" height=\"12\" fill=\"").append(color).append("\"/>\n");
		svgBuilder.append("<text x=\"").append(x + 18).append("\" y=\"").append(y)
			.append("\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#333\">").append(label).append("</text>\n");
		return x + 30 + label.length() * 7;
	}

	private double elapsedMillis(long nanos) {
		return (nanos - this.firstSampleNanos) / 1_000_000.0;
	}

	private static double x(double value, double maxX, int left, int plotWidth) {
		return left + (maxX <= 0 ? 0 : value / maxX) * plotWidth;
	}

	private static double y(double value, double maxY, int top, int plotHeight) {
		return top + plotHeight - (maxY <= 0 ? 0 : value / maxY) * plotHeight;
	}

	private static double niceCeiling(double value) {
		double magnitude = Math.pow(10, Math.floor(Math.log10(Math.max(1, value))));
		return Math.ceil(value / magnitude) * magnitude;
	}

	private static String fmt(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private record Sample(long nanos, int platformThreads, int mountedVirtualThreads, long queuedVirtualThreads,
			int carrierPoolSize, int carrierParallelism, int activeRequests) {
	}

	private record Plot(int top, int height) {
	}

	private record Iteration(boolean warmup, int index, long startNanos, long endNanos) {
	}

	@FunctionalInterface
	private interface SampleValue {

		double get(Sample sample);

	}

}
