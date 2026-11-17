package ru.maximserver.vthreads;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import ru.maximserver.bench.BenchmarkIdle;
import ru.maximserver.bench.ChatBenchmarkApp;

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(1)
@State(Scope.Benchmark)
public class ChatBenchmark {

	private static final int REQUESTS = 1_000;

	private static final boolean EXTERNAL_APP = !"inprocess"
		.equalsIgnoreCase(System.getenv().getOrDefault("BENCH_APP_MODE", "external"));

	private static final String APP_URL_PROPERTY = "bench.app.url";

	private static final String[] WORDS = { "hello", "привет", "kafka", "virtual", "thread", "chat", "spring", "java",
			"benchmark", "message", "question", "answer", "alpha", "beta", "gamma", "delta", "stream", "event",
			"request", "response" };

	@Param({ "1", "10", "100" })
	private int concurrency;

	private ConfigurableApplicationContext context;

	private Process appProcess;

	private Path appPortFile;

	private Path appLogFile;

	private String appProfilePrefix;

	private String appUrl;

	private RestClient restClient;

	private String[] requests;

	@Setup(Level.Trial)
	public void setUp() throws Exception {
		String groupId = "chat-benchmark-" + UUID.randomUUID();
		String requestTopic = "chat-benchmark-request-" + UUID.randomUUID();
		String responseTopic = "chat-benchmark-response-" + UUID.randomUUID();
		String responseDelay = System.getenv().getOrDefault("BENCH_RESPONSE_DELAY", "1ms");
		String responseTimeout = System.getenv().getOrDefault("BENCH_RESPONSE_TIMEOUT", "60s");

		if (EXTERNAL_APP) {
			startExternalApp(groupId, requestTopic, responseTopic, responseDelay, responseTimeout);
		}
		else {
			startInProcessApp(groupId, requestTopic, responseTopic, responseDelay, responseTimeout);
		}

		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		this.restClient = RestClient.builder()
			.baseUrl(this.appUrl)
			.requestFactory(new JdkClientHttpRequestFactory(httpClient))
			.build();

		SplittableRandom random = new SplittableRandom(System.nanoTime());
		this.requests = new String[REQUESTS];
		for (int i = 0; i < REQUESTS; i++) {
			this.requests[i] = randomRequest(random);
		}
	}

	private void startInProcessApp(String groupId, String requestTopic, String responseTopic, String responseDelay,
			String responseTimeout) {
		System.clearProperty(APP_URL_PROPERTY);
		this.context = new SpringApplicationBuilder(VthreadsApplication.class)
			.run("--server.port=0", "--server.tomcat.accept-count=2000", "--server.tomcat.max-connections=10000",
					"--spring.kafka.consumer.group-id=" + groupId,
					"--spring.kafka.consumer.auto-offset-reset=earliest",
					"--app.kafka.chat-request-topic=" + requestTopic,
					"--app.kafka.chat-response-topic=" + responseTopic, "--app.chat.response-delay=" + responseDelay,
					"--app.chat.response-timeout=" + responseTimeout, "--springdoc.api-docs.enabled=false",
					"--springdoc.swagger-ui.enabled=false", "--logging.level.root=warn");
		int port = ((WebServerApplicationContext) this.context).getWebServer().getPort();
		this.appUrl = "http://localhost:" + port;
	}

	private void startExternalApp(String groupId, String requestTopic, String responseTopic, String responseDelay,
			String responseTimeout) throws Exception {
		Path targetDir = Path.of("target").toAbsolutePath();
		Files.createDirectories(targetDir);
		this.appProfilePrefix = targetDir
			.resolve("jmh-app-thread-usage-concurrency-" + this.concurrency + "-" + System.currentTimeMillis())
			.toString();
		this.appPortFile = targetDir.resolve("jmh-app-port-" + UUID.randomUUID() + ".txt");
		this.appLogFile = Path.of(this.appProfilePrefix + ".app.log");
		Files.deleteIfExists(this.appPortFile);

		List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(ChatBenchmarkApp.class.getName());
		command.add("--server.port=0");
		command.add("--server.tomcat.accept-count=2000");
		command.add("--server.tomcat.max-connections=10000");
		command.add("--spring.kafka.consumer.group-id=" + groupId);
		command.add("--spring.kafka.consumer.auto-offset-reset=earliest");
		command.add("--app.kafka.chat-request-topic=" + requestTopic);
		command.add("--app.kafka.chat-response-topic=" + responseTopic);
		command.add("--app.chat.response-delay=" + responseDelay);
		command.add("--app.chat.response-timeout=" + responseTimeout);
		command.add("--springdoc.api-docs.enabled=false");
		command.add("--springdoc.swagger-ui.enabled=false");
		command.add("--logging.level.root=warn");

		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(new File(System.getProperty("user.dir")));
		processBuilder.redirectErrorStream(true);
		processBuilder.redirectOutput(this.appLogFile.toFile());
		processBuilder.environment().put("BENCH_PORT_FILE", this.appPortFile.toString());
		processBuilder.environment().put("BENCH_PROFILE_PREFIX", this.appProfilePrefix);
		processBuilder.environment()
			.put("BENCH_PROFILE_INTERVAL_MS", System.getenv().getOrDefault("BENCH_PROFILE_INTERVAL_MS", "50"));

		this.appProcess = processBuilder.start();
		try {
			int port = waitForAppPort();
			this.appUrl = "http://localhost:" + port;
			System.setProperty(APP_URL_PROPERTY, this.appUrl);
			System.out.println("[external-app] url=" + this.appUrl + ", profile=" + this.appProfilePrefix + ", log="
					+ this.appLogFile.toAbsolutePath());
		}
		catch (Exception ex) {
			this.appProcess.destroyForcibly();
			throw ex;
		}
	}

	private int waitForAppPort() throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
		while (System.nanoTime() < deadline) {
			if (!this.appProcess.isAlive()) {
				throw new IllegalStateException("Benchmark app exited with code " + this.appProcess.exitValue()
						+ ", see log " + this.appLogFile.toAbsolutePath());
			}
			if (Files.exists(this.appPortFile)) {
				String content = Files.readString(this.appPortFile).trim();
				if (!content.isEmpty()) {
					return Integer.parseInt(content);
				}
			}
			Thread.sleep(100);
		}
		throw new IllegalStateException(
				"Timed out waiting for benchmark app port file " + this.appPortFile.toAbsolutePath());
	}

	@Benchmark
	@OperationsPerInvocation(REQUESTS)
	public void thousandChatRequests(Blackhole blackhole) throws Exception {
		ThreadUsageProfiler.startPendingIteration(System.getProperty(APP_URL_PROPERTY));
		Semaphore permits = new Semaphore(this.concurrency);
		List<Future<String>> responses = new ArrayList<>(REQUESTS);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < REQUESTS; i++) {
				String request = this.requests[i];
				responses.add(executor.submit(() -> {
					permits.acquire();
					try {
						return this.restClient.post()
							.uri("/chat")
							.contentType(MediaType.TEXT_PLAIN)
							.body(request)
							.retrieve()
							.body(String.class);
					}
					finally {
						permits.release();
					}
				}));
			}
			for (Future<String> response : responses) {
				blackhole.consume(response.get());
			}
		}
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		// JMH runs the trial teardown inside the last iteration, before the profiler's
		// afterIteration. Close the last measurement iteration and keep the load stopped for
		// the idle period while the sampler is still running, so the chart shows the release.
		if (EXTERNAL_APP) {
			endExternalIteration();
			BenchmarkIdle.pause();
			BenchmarkIdle.markCompleted();
			stopExternalApp();
		}
		else if (this.context != null) {
			ThreadUsageRecorder.endCurrentIteration();
			BenchmarkIdle.pause();
			BenchmarkIdle.markCompleted();
			this.context.close();
		}
	}

	private void endExternalIteration() {
		if (this.appUrl == null) {
			return;
		}
		try {
			post(this.appUrl + "/__bench/iteration/end?type=MEASUREMENT&index=0");
		}
		catch (Exception ex) {
			System.err.println("[external-app] failed to close the last iteration: " + ex);
		}
	}

	private void stopExternalApp() {
		if (this.appProcess == null) {
			return;
		}
		try {
			post(this.appUrl + "/__bench/stop");
		}
		catch (Exception ex) {
			System.err.println("[external-app] failed to stop app gracefully: " + ex);
		}
		try {
			if (!this.appProcess.waitFor(30, TimeUnit.SECONDS)) {
				this.appProcess.destroyForcibly();
				this.appProcess.waitFor(10, TimeUnit.SECONDS);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		finally {
			System.clearProperty(APP_URL_PROPERTY);
			if (this.appPortFile != null) {
				try {
					Files.deleteIfExists(this.appPortFile);
				}
				catch (IOException ignored) {
				}
			}
		}
	}

	private static void post(String url) throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(5))
			.POST(HttpRequest.BodyPublishers.noBody())
			.build();
		client.send(request, HttpResponse.BodyHandlers.discarding());
	}

	private static String javaExecutable() {
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
	}

	private static String randomRequest(SplittableRandom random) {
		int wordCount = 1 + random.nextInt(20);
		StringBuilder request = new StringBuilder(64);
		for (int i = 0; i < wordCount; i++) {
			if (i > 0) {
				request.append(' ');
			}
			request.append(WORDS[random.nextInt(WORDS.length)]);
		}
		return request.append(" #").append(Long.toHexString(random.nextLong())).toString();
	}

}
