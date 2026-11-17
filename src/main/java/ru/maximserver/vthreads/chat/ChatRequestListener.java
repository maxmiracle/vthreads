package ru.maximserver.vthreads.chat;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChatRequestListener {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	private final String chatResponseTopic;

	private final Duration responseDelay;

	public ChatRequestListener(
			KafkaTemplate<String, Object> kafkaTemplate,
			@Value("${app.kafka.chat-response-topic}") String chatResponseTopic,
			@Value("${app.chat.response-delay}") Duration responseDelay) {
		this.kafkaTemplate = kafkaTemplate;
		this.chatResponseTopic = chatResponseTopic;
		this.responseDelay = responseDelay;
	}

	@KafkaListener(topics = "${app.kafka.chat-request-topic}")
	public void onChatRequest(ChatRequestMessage message) {
		try {
			Thread.sleep(this.responseDelay.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return;
		}
		String chatResponse = "Ответ " + UUID.randomUUID();
		ChatResponseMessage response = new ChatResponseMessage(message.id(), chatResponse);
		this.kafkaTemplate.send(this.chatResponseTopic, message.id().toString(), response).join();
	}
}
