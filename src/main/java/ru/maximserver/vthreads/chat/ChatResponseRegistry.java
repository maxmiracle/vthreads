package ru.maximserver.vthreads.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ChatResponseRegistry {

	private final Map<UUID, CompletableFuture<String>> pendingResponses = new ConcurrentHashMap<>();

	public CompletableFuture<String> register(UUID id) {
		CompletableFuture<String> future = new CompletableFuture<>();
		this.pendingResponses.put(id, future);
		return future;
	}

	public void complete(UUID id, String response) {
		CompletableFuture<String> future = this.pendingResponses.remove(id);
		if (future != null) {
			future.complete(response);
		}
	}

	public void remove(UUID id) {
		this.pendingResponses.remove(id);
	}
}
