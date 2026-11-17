package ru.maximserver.vthreads.chat;

import java.time.Instant;
import java.util.UUID;

public record ChatRequestMessage(
		UUID id,
		String request,
		Instant requestTime) {
}
