package ru.maximserver.vthreads.chat;

import java.time.Instant;
import java.util.UUID;

public record Chat(
		UUID id,
		String request,
		Instant requestTime,
		String response,
		Instant responseTime) {
}
