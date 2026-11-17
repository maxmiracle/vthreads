package ru.maximserver.vthreads.chat;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfiguration {

	@Bean
	NewTopic chatRequestTopic(@Value("${app.kafka.chat-request-topic}") String topic) {
		return TopicBuilder.name(topic).partitions(1).replicas(1).build();
	}

	@Bean
	NewTopic chatResponseTopic(@Value("${app.kafka.chat-response-topic}") String topic) {
		return TopicBuilder.name(topic).partitions(1).replicas(1).build();
	}
}
