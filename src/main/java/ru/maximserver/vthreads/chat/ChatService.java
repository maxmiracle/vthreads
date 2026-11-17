package ru.maximserver.vthreads.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {

	private final ChatRepository chatRepository;

	private final ChatResponseRegistry responseRegistry;

	private final KafkaTemplate<String, Object> kafkaTemplate;

	private final String chatRequestTopic;

	private final Duration responseTimeout;

	public ChatService(
			ChatRepository chatRepository,
			ChatResponseRegistry responseRegistry,
			KafkaTemplate<String, Object> kafkaTemplate,
			@Value("${app.kafka.chat-request-topic}") String chatRequestTopic,
			@Value("${app.chat.response-timeout}") Duration responseTimeout) {
		this.chatRepository = chatRepository;
		this.responseRegistry = responseRegistry;
		this.kafkaTemplate = kafkaTemplate;
		this.chatRequestTopic = chatRequestTopic;
		this.responseTimeout = responseTimeout;
	}

	public String chat(String request) {
		UUID id = UUID.randomUUID();
		Instant requestTime = Instant.now();
		this.chatRepository.insert(new Chat(id, request, requestTime, null, null));

		CompletableFuture<String> responseFuture = this.responseRegistry.register(id);
		try {
			ChatRequestMessage message = new ChatRequestMessage(id, request, requestTime);
			this.kafkaTemplate.send(this.chatRequestTopic, id.toString(), message)
				.get(this.responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
			return responseFuture.get(this.responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Chat request was interrupted", ex);
		}
		catch (TimeoutException ex) {
			throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Chat response timed out", ex);
		}
		catch (ExecutionException ex) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process chat request", ex);
		}
		finally {
			this.responseRegistry.remove(id);
		}
	}
}
