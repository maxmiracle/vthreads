package ru.maximserver.bench;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import ru.maximserver.vthreads.ThreadUsageRecorder;
import ru.maximserver.vthreads.VthreadsApplication;

public final class ChatBenchmarkApp {

	private ChatBenchmarkApp() {
	}

	public static void main(String[] args) throws Exception {
		String portFile = System.getenv("BENCH_PORT_FILE");
		String profilePrefix = System.getenv().getOrDefault("BENCH_PROFILE_PREFIX",
				"target/jmh-app-thread-usage-" + System.currentTimeMillis());
		long intervalMillis = Long.parseLong(System.getenv().getOrDefault("BENCH_PROFILE_INTERVAL_MS", "50"));

		ConfigurableApplicationContext context = new SpringApplicationBuilder(VthreadsApplication.class,
				BenchmarkControl.class).run(args);

		BenchmarkControl control = context.getBean(BenchmarkControl.class);
		ThreadUsageRecorder recorder = new ThreadUsageRecorder(profilePrefix, intervalMillis);
		control.attach(recorder);
		recorder.start();

		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		if (portFile != null) {
			Files.writeString(Path.of(portFile), Integer.toString(port));
		}
		else {
			System.out.println("[bench-app] server.port=" + port);
		}
	}

}
