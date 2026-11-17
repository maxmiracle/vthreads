package ru.maximserver.vthreads.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChatRepository {

	private static final RowMapper<Chat> CHAT_ROW_MAPPER = ChatRepository::mapChat;

	private final JdbcClient jdbcClient;

	public ChatRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public void insert(Chat chat) {
		this.jdbcClient.sql("""
				insert into chat (id, request, request_time)
				values (:id, :request, :requestTime)
				""")
			.param("id", chat.id())
			.param("request", chat.request())
			.param("requestTime", toOffsetDateTime(chat.requestTime()))
			.update();
	}

	public void updateResponse(UUID id, String response, Instant responseTime) {
		this.jdbcClient.sql("""
				update chat
				set response = :response, response_time = :responseTime
				where id = :id
				""")
			.param("id", id)
			.param("response", response)
			.param("responseTime", toOffsetDateTime(responseTime))
			.update();
	}

	public Optional<Chat> findById(UUID id) {
		return this.jdbcClient.sql("""
				select id, request, request_time, response, response_time
				from chat
				where id = :id
				""")
			.param("id", id)
			.query(CHAT_ROW_MAPPER)
			.optional();
	}

	private static Chat mapChat(ResultSet resultSet, int rowNum) throws SQLException {
		return new Chat(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("request"),
				toInstant(resultSet.getObject("request_time", OffsetDateTime.class)),
				resultSet.getString("response"),
				toInstant(resultSet.getObject("response_time", OffsetDateTime.class)));
	}

	private static OffsetDateTime toOffsetDateTime(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant toInstant(OffsetDateTime offsetDateTime) {
		return (offsetDateTime != null) ? offsetDateTime.toInstant() : null;
	}
}
