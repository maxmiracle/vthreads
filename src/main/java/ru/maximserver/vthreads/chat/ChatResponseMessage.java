package ru.maximserver.vthreads.chat;

import java.util.UUID;

public record ChatResponseMessage(
		UUID id,
		String chatResponse) {
}
