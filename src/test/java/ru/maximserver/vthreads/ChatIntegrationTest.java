package ru.maximserver.vthreads;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"app.chat.response-delay=500ms",
		"app.chat.response-timeout=20s"
})
class ChatIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbcClient;

	@Test
	void chatReturnsKafkaResponseAndPersistsIt() {
		String request = "hello-" + UUID.randomUUID();
		RestClient restClient = RestClient.create("http://localhost:" + this.port);

		String response = restClient.post()
			.uri("/chat")
			.contentType(MediaType.TEXT_PLAIN)
			.body(request)
			.retrieve()
			.body(String.class);

		assertThat(response).startsWith("Ответ ");
		UUID.fromString(response.substring("Ответ ".length()));

		List<Map<String, Object>> rows = this.jdbcClient.sql("""
				select id, request, request_time, response, response_time
				from chat
				where request = :request
				""")
			.param("request", request)
			.query()
			.listOfRows();

		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.getFirst();
		assertThat(row.get("response")).isEqualTo(response);
		assertThat(row.get("request_time")).isNotNull();
		assertThat(row.get("response_time")).isNotNull();
	}

}
