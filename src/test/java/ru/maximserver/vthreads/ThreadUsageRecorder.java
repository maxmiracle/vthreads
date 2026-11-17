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

public final class ThreadUsageRecorder {

	private static final long DEFAULT_INTERVAL_MILLIS = 50;

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
			else {
				this.iterations.add(new Iteration(warmup, index, this.firstSampleNanos, System.nanoTime()));
			}
			writeOutputs();
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
					this.virtualThreadSchedulerMXBean.getParallelism());
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
					"elapsed_ms,platform_threads,mounted_virtual_threads,queued_virtual_threads,carrier_pool_size,carrier_parallelism\n");
			for (Sample sample : this.samples) {
				writer.write(String.format(Locale.ROOT, "%.1f,%d,%d,%d,%d,%d%n",
						elapsedMillis(sample.nanos()), sample.platformThreads(), sample.mountedVirtualThreads(),
						sample.queuedVirtualThreads(), sample.carrierPoolSize(), sample.carrierParallelism()));
			}
		}
	}

	private void writeSvg(Path svg) throws IOException {
		double maxX = 1;
		double maxY = 1;
		for (Sample sample : this.samples) {
			maxX = Math.max(maxX, elapsedMillis(sample.nanos()));
			maxY = Math.max(maxY, Math.max(sample.platformThreads(),
					Math.max(sample.mountedVirtualThreads(), Math.max(sample.queuedVirtualThreads(),
							sample.carrierPoolSize()))));
		}
		for (Iteration iteration : this.iterations) {
			maxX = Math.max(maxX, elapsedMillis(iteration.endNanos()));
		}

		int width = 1200;
		int height = 620;
		int left = 70;
		int top = 70;
		int plotWidth = width - left - 30;
		int plotHeight = height - top - 60;
		double axisMaxY = niceCeiling(maxY);

		StringBuilder svgBuilder = new StringBuilder(16_384);
		svgBuilder.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width).append("\" height=\"")
			.append(height).append("\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">\n");
		svgBuilder.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
		svgBuilder.append("<text x=\"").append(width / 2).append("\" y=\"30\" text-anchor=\"middle\" ")
			.append("font-family=\"sans-serif\" font-size=\"18\" font-weight=\"bold\">")
			.append("Thread usage: platform vs virtual threads</text>\n");

		for (Iteration iteration : this.iterations) {
			double x1 = x(elapsedMillis(iteration.startNanos()), maxX, left, plotWidth);
			double x2 = x(elapsedMillis(iteration.endNanos()), maxX, left, plotWidth);
			String fill = iteration.warmup() ? "#9e9e9e" : "#4c78a8";
			String opacity = iteration.warmup() ? "0.12" : "0.10";
			svgBuilder.append("<rect x=\"").append(fmt(x1)).append("\" y=\"").append(top).append("\" width=\"")
				.append(fmt(Math.max(0, x2 - x1))).append("\" height=\"").append(plotHeight).append("\" fill=\"")
				.append(fill).append("\" fill-opacity=\"").append(opacity).append("\"/>\n");
			svgBuilder.append("<text x=\"").append(fmt((x1 + x2) / 2)).append("\" y=\"").append(top + 14)
				.append("\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"10\" fill=\"#666\">")
				.append(iteration.warmup() ? "warmup " : "measurement ").append(iteration.index()).append("</text>\n");
		}

		for (int i = 0; i <= 5; i++) {
			double value = axisMaxY * i / 5;
			double y = y(value, axisMaxY, top, plotHeight);
			svgBuilder.append("<line x1=\"").append(left).append("\" y1=\"").append(fmt(y)).append("\" x2=\"")
				.append(left + plotWidth).append("\" y2=\"").append(fmt(y))
				.append("\" stroke=\"#e0e0e0\" stroke-width=\"1\"/>\n");
			svgBuilder.append("<text x=\"").append(left - 10).append("\" y=\"").append(fmt(y + 4))
				.append("\" text-anchor=\"end\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#555\">")
				.append(fmt(value)).append("</text>\n");
		}
		for (int i = 0; i <= 5; i++) {
			double value = maxX * i / 5;
			double x = x(value, maxX, left, plotWidth);
			svgBuilder.append("<line x1=\"").append(fmt(x)).append("\" y1=\"").append(top).append("\" x2=\"")
				.append(fmt(x)).append("\" y2=\"").append(top + plotHeight)
				.append("\" stroke=\"#f0f0f0\" stroke-width=\"1\"/>\n");
			svgBuilder.append("<text x=\"").append(fmt(x)).append("\" y=\"").append(top + plotHeight + 20)
				.append("\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#555\">")
				.append(fmt(value)).append("</text>\n");
		}
		svgBuilder.append("<text x=\"").append(left + plotWidth / 2).append("\" y=\"").append(height - 12)
			.append("\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#333\">")
			.append("elapsed time, ms</text>\n");
		svgBuilder.append("<text x=\"18\" y=\"").append(top + plotHeight / 2)
			.append("\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#333\" ")
			.append("transform=\"rotate(-90 18 ").append(top + plotHeight / 2).append(")\">threads</text>\n");

		appendSeries(svgBuilder, "Platform threads", "#e45756", sample -> sample.platformThreads(), maxX, axisMaxY,
				left, top, plotWidth, plotHeight);
		appendSeries(svgBuilder, "Mounted virtual threads", "#4c78a8",
				sample -> sample.mountedVirtualThreads(), maxX, axisMaxY, left, top, plotWidth, plotHeight);
		appendSeries(svgBuilder, "Queued virtual threads", "#f58518", sample -> sample.queuedVirtualThreads(),
				maxX, axisMaxY, left, top, plotWidth, plotHeight);
		appendSeries(svgBuilder, "Carrier pool size", "#54a24b", sample -> sample.carrierPoolSize(), maxX,
				axisMaxY, left, top, plotWidth, plotHeight);

		int legendY = 48;
		int legendX = left;
		legendX = appendLegend(svgBuilder, legendX, legendY, "Platform threads", "#e45756");
		legendX = appendLegend(svgBuilder, legendX, legendY, "Mounted virtual threads", "#4c78a8");
		legendX = appendLegend(svgBuilder, legendX, legendY, "Queued virtual threads", "#f58518");
		appendLegend(svgBuilder, legendX, legendY, "Carrier pool size", "#54a24b");

		svgBuilder.append("</svg>\n");
		Files.writeString(svg, svgBuilder.toString());
	}

	private void appendSeries(StringBuilder svgBuilder, String label, String color, SampleValue value, double maxX,
			double maxY, int left, int top, int plotWidth, int plotHeight) {
		svgBuilder.append("<polyline fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"2\" points=\"");
		boolean first = true;
		for (Sample sample : this.samples) {
			double x = x(elapsedMillis(sample.nanos()), maxX, left, plotWidth);
			double y = y(value.get(sample), maxY, top, plotHeight);
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
			int carrierPoolSize, int carrierParallelism) {
	}

	private record Iteration(boolean warmup, int index, long startNanos, long endNanos) {
	}

	@FunctionalInterface
	private interface SampleValue {

		double get(Sample sample);

	}

}
