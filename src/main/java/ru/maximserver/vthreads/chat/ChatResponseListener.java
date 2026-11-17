package ru.maximserver.vthreads.chat;

import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ChatResponseListener {

	private final ChatRepository chatRepository;

	private final ChatResponseRegistry responseRegistry;

	public ChatResponseListener(ChatRepository chatRepository, ChatResponseRegistry responseRegistry) {
		this.chatRepository = chatRepository;
		this.responseRegistry = responseRegistry;
	}

	@KafkaListener(topics = "${app.kafka.chat-response-topic}")
	public void onChatResponse(ChatResponseMessage message) {
		this.chatRepository.updateResponse(message.id(), message.chatResponse(), Instant.now());
		this.responseRegistry.complete(message.id(), message.chatResponse());
	}
}
