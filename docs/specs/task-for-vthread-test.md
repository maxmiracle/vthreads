# VThreads tests

## Technology/Libraries/Stack
- JDK 25
- Spring Boot 4
- Lombok
- Postgres
- Kafka(ALO)
- liquibase
- Virtual Threads enabled
- Для конфигурации приложения используем application.yaml c environment переменными, но с дефолтными значениями.

## REST API 
### POST chat(String request): returns response String
1. Генерируем id uuid,
1. Создаем запись в БД в таблице chat запись с id, request, requestTime, response=null, responseTime=null
2. Отправляем в kafka запрос в топик chat-request c id, request, requestTime.
3. Ждем получения из топика chat-response сообщения с id, chatResponse.
4. Отправляем ответ в rest.

### Kafka 
Listener chat-request
Получаем запрос и генерируем uuid.
Имитируем задержку 10 сек.
Возвращаем сообщение "Ответ <uuid>" в топик chat-response.

### Дополнительно
Сделать тесты c Testcontainers(postgres, kafka)
Сделать docker-compose для возможности локального запуска на машине разработчика.


