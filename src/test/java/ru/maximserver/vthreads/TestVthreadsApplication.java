package ru.maximserver.vthreads;

import org.springframework.boot.SpringApplication;

public class TestVthreadsApplication {

	static void main(String[] args) {
		SpringApplication.from(VthreadsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
