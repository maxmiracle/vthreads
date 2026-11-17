package ru.maximserver.bench;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.maximserver.vthreads.ThreadUsageRecorder;

@RestController
@RequestMapping("/__bench")
public class BenchmarkControl {

	private volatile ThreadUsageRecorder recorder;

	public void attach(ThreadUsageRecorder recorder) {
		this.recorder = recorder;
	}

	@PostMapping("/iteration/start")
	public String iterationStart(@RequestParam String type, @RequestParam(defaultValue = "0") int index) {
		ThreadUsageRecorder current = this.recorder;
		if (current != null) {
			current.startIteration("WARMUP".equalsIgnoreCase(type), index);
		}
		return "ok";
	}

	@PostMapping("/iteration/end")
	public String iterationEnd(@RequestParam String type, @RequestParam(defaultValue = "0") int index) {
		ThreadUsageRecorder current = this.recorder;
		if (current != null) {
			current.endIteration("WARMUP".equalsIgnoreCase(type), index);
		}
		return "ok";
	}

	@PostMapping("/stop")
	public String stop() {
		Thread thread = new Thread(() -> {
			ThreadUsageRecorder current = this.recorder;
			if (current != null) {
				current.stop();
			}
			System.exit(0);
		}, "bench-app-stop");
		thread.setDaemon(false);
		thread.start();
		return "stopping";
	}

}
