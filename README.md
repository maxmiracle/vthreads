# VThreads

Демонстрационное приложение на Spring Boot 4 / JDK 25 с включёнными виртуальными потоками.
Запрос `POST /chat` сохраняется в PostgreSQL, отправляется в Kafka, обрабатывается listener-ом
и возвращает ответ через второй Kafka-топик. В репозитории также есть JMH-бенчмарк для 1000
chat-запросов со случайными параметрами и профилировщик использования платформенных и
виртуальных потоков и количества активных запросов.

## Содержание

- [Требования](#требования)
- [Инфраструктура](#инфраструктура)
- [Запуск приложения](#запуск-приложения)
- [JMH-бенчмарк](#jmh-бенчмарк)
- [Варианты запуска JMH](#варианты-запуска-jmh)
- [Профилирование потоков](#профилирование-потоков)
- [Как читать результаты](#как-читать-результаты)
- [Эксперимент VT ON vs VT OFF](#эксперимент-vt-on-vs-vt-off)
- [Статья о virtual threads](docs/article/virtual-threads-spring-boot-jdk25-v2.md)
- [Решение проблем](#решение-проблем)

## Требования

- JDK 25;
- Maven 3.9+;
- Docker с `docker compose`.

## Инфраструктура

PostgreSQL и Kafka описаны в `docker-compose.yml`:

```bash
docker compose up -d
```

Проверить, что контейнеры поднялись:

```bash
docker compose ps
```

Остановить инфраструктуру:

```bash
docker compose down
```

## Запуск приложения

```bash
docker compose up -d
mvn spring-boot:run
```

Приложение слушает `http://localhost:8080`, Swagger UI доступен по адресу
`http://localhost:8080/swagger-ui.html`.

Проверка chat-запроса:

```bash
curl.exe -X POST http://localhost:8080/chat -H "Content-Type: text/plain" -d "hello"
```

## JMH-бенчмарк

Бенчмарк находится в `src/test/java/ru/maximserver/vthreads/ChatBenchmark.java`.

### Что делает бенчмарк

- За один вызов метода `thousandChatRequests` отправляет ровно **1000** HTTP-запросов `POST /chat`.
- Текст каждого запроса генерируется случайно: случайное число слов и случайный суффикс.
- По умолчанию запускает приложение **отдельным JVM-процессом** (`BENCH_APP_MODE=external`),
  а сам JMH-клиент остаётся в другом процессе. Это позволяет профилировать только потоки
  приложения, без потоков генератора нагрузки.
- Поддерживается и режим `BENCH_APP_MODE=inprocess`, когда приложение поднимается прямо в
  JVM JMH-бенчмарка.
- Для изоляции использует уникальные Kafka-топики и `consumer group` на каждый прогон, поэтому
  не конфликтует с отдельно запущенным приложением.
- Режим JMH: `SingleShotTime` + `@OperationsPerInvocation(1000)`, поэтому итоговый `Score`
  в `ms/op` — это среднее время **одного** запроса. Время всей пачки из 1000 запросов =
  `Score × 1000`.
- Параметр `concurrency` задаёт, сколько запросов одновременно находится «в полёте»
  (доступны значения `1`, `10`, `100`).
- После каждой measurement-итерации нагрузка снимается на 10 секунд: запросов больше нет,
  и на графике видно, как высвобождаются виртуальные и платформенные потоки. Длительность
  паузы задаётся переменной `BENCH_IDLE_MILLIS` (по умолчанию `10000` мс, `0` отключает паузу).
  Пауза не входит в замер JMH, поэтому `Score` не меняется.

Бенчмарк использует те же PostgreSQL и Kafka, что и приложение, поэтому перед запуском нужен
`docker compose up -d`.

### Режимы запуска приложения

Режим задаётся переменной окружения `BENCH_APP_MODE`:

| Режим | Значение | Описание |
| --- | --- | --- |
| Внешний процесс (по умолчанию) | `external` | Приложение стартует отдельной JVM, JMH-клиент работает в своей JVM. Профилировщик пишет график только по потокам приложения. |
| In-process | `inprocess` | Приложение и JMH-клиент находятся в одной JVM. Быстрее стартует, но в график попадают и виртуальные потоки клиента. |

Переключение на in-process (PowerShell):

```powershell
$env:BENCH_APP_MODE="inprocess"
mvn test-compile exec:exec "-Djmh.concurrency=100"
```

bash:

```bash
BENCH_APP_MODE=inprocess mvn test-compile exec:exec "-Djmh.concurrency=100"
```

Во внешнем режиме в выводе JMH печатается строка `[external-app] url=...`, а также пути к
файлам профиля и логу дочернего процесса.

### Быстрый старт

```bash
docker compose up -d
mvn test-compile exec:exec
```

По умолчанию прогоняются все три уровня параллелизма (`1`, `10`, `100`), по 2 warmup- и
3 measurement-итерации.

> В PowerShell параметры вида `-Djmh...` лучше брать в кавычки:
> `mvn exec:exec "-Djmh.concurrency=10"`.

## Варианты запуска JMH

Все варианты предполагают, что инфраструктура уже запущена (`docker compose up -d`).
Настройки JMH вынесены в свойства Maven в `pom.xml` и переопределяются через `-D`.

| Вариант | Команда | Описание |
| --- | --- | --- |
| Полный прогон (внешний процесс) | `mvn test-compile exec:exec` | Все уровни `concurrency` (`1`, `10`, `100`), 2 warmup + 3 measurement итерации. Приложение в отдельной JVM, профилируются только его потоки. |
| In-process профилирование | PowerShell: `$env:BENCH_APP_MODE="inprocess"; mvn test-compile exec:exec` | Приложение поднимается в JVM JMH-клиента. В график попадают и виртуальные потоки генератора нагрузки. Быстрее стартует. |
| Только последовательно | `mvn test-compile exec:exec "-Djmh.concurrency=1"` | 1000 запросов строго по одному. Показывает базовую latency без параллелизма. |
| Небольшая параллельность | `mvn test-compile exec:exec "-Djmh.concurrency=10"` | До 10 одновременных запросов. |
| Высокая параллельность | `mvn test-compile exec:exec "-Djmh.concurrency=100"` | До 100 одновременных запросов. Хорошо показывает выигрыш виртуальных потоков. |
| Быстрый smoke-прогон | `mvn test-compile exec:exec "-Djmh.concurrency=100" "-Djmh.warmupIterations=0" "-Djmh.measurementIterations=1"` | Минимум времени: одна итерация без warmup. Подходит для проверки, что всё работает. |
| Более точный замер | `mvn test-compile exec:exec "-Djmh.concurrency=100" "-Djmh.forks=2" "-Djmh.warmupIterations=5" "-Djmh.measurementIterations=10"` | Больше fork-процессов и итераций, меньше шума. Занимает заметно больше времени. |
| С искусственной задержкой | PowerShell: `$env:BENCH_RESPONSE_DELAY="10ms"; mvn test-compile exec:exec "-Djmh.concurrency=100"` | Имитирует медленный ответ Kafka-listener-а (по умолчанию `1ms`). |
| С другим таймаутом | PowerShell: `$env:BENCH_RESPONSE_TIMEOUT="120s"; mvn test-compile exec:exec` | Таймаут ожидания ответа chat-запроса (по умолчанию `60s`). |
| Без паузы после нагрузки | PowerShell: `$env:BENCH_IDLE_MILLIS="0"; mvn test-compile exec:exec` | Отключает 10-секундный период без нагрузки после measurement-итераций. |
| Другая пауза после нагрузки | PowerShell: `$env:BENCH_IDLE_MILLIS="30000"; mvn test-compile exec:exec "-Djmh.concurrency=100"` | Меняет длительность периода без нагрузки: видно, как запросы завершаются и потоки высвобождаются (по умолчанию `10000` мс). |
| Другой профилировщик | `mvn test-compile exec:exec "-Djmh.profiler=gc"` | Меняет JMH-профилировщик на встроенный (например, `gc`, `stack`, `comp`). Во внешнем режиме сэмплер приложения продолжает работать, но пропадает разметка итераций. |

Для bash задержку, таймаут и паузу после нагрузки задают так:

```bash
BENCH_RESPONSE_DELAY=10ms BENCH_RESPONSE_TIMEOUT=120s BENCH_IDLE_MILLIS=30000 mvn test-compile exec:exec
```

### Свойства Maven

| Свойство | По умолчанию | Назначение |
| --- | --- | --- |
| `jmh.includes` | `ChatBenchmark` | Имя/pattern запускаемого бенчмарка (позиционный аргумент JMH). |
| `jmh.forks` | `1` | Количество fork-процессов JMH (`-f`). |
| `jmh.warmupIterations` | `2` | Количество warmup-итераций (`-wi`). |
| `jmh.measurementIterations` | `3` | Количество measurement-итераций (`-i`). |
| `jmh.concurrency` | `1,10,100` | Значения параметра `concurrency` для `@Param` (`-p concurrency=...`). |
| `jmh.profiler` | `ru.maximserver.vthreads.ThreadUsageProfiler` | Профилировщик JMH (`-prof`). |

### Переменные окружения

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `BENCH_APP_MODE` | `external` | `external` — приложение в отдельном процессе, `inprocess` — в JVM JMH-клиента. |
| `BENCH_RESPONSE_DELAY` | `1ms` | Искусственная задержка ответа Kafka-listener-а. |
| `BENCH_RESPONSE_TIMEOUT` | `60s` | Таймаут ожидания ответа chat-запроса. |
| `BENCH_IDLE_MILLIS` | `10000` | Пауза без нагрузки после каждой measurement-итерации (мс), чтобы на графике было видно высвобождение потоков. `0` отключает паузу. |
| `BENCH_PROFILE_INTERVAL_MS` | `50` | Интервал сэмплирования потоков в процессе приложения (внешний режим). |

Пример с несколькими переопределениями:

```bash
mvn test-compile exec:exec "-Djmh.concurrency=10,100" "-Djmh.warmupIterations=3" "-Djmh.measurementIterations=5"
```

## Профилирование потоков

Сэмплирование и построение CSV/SVG находятся в
`src/test/java/ru/maximserver/vthreads/ThreadUsageRecorder.java`. JMH-профилировщик
`ThreadUsageProfiler` включён по умолчанию через
`-prof ru.maximserver.vthreads.ThreadUsageProfiler` и работает так:

- во внешнем режиме (по умолчанию) он только передаёт в приложение границы
  warmup/measurement-итераций, а сэмплер работает внутри JVM приложения;
- в in-process режиме он сам сэмплирует JVM, в которой одновременно находятся
  приложение и JMH-клиент.

### Что сэмплируется

Сэмплер раз в 50 мс (по умолчанию) снимает показатели:

- `ThreadMXBean.getThreadCount()` — количество платформенных потоков JVM;
- `VirtualThreadSchedulerMXBean.getMountedVirtualThreadCount()` — виртуальные потоки,
  которые сейчас выполняются на carrier-потоках;
- `VirtualThreadSchedulerMXBean.getQueuedVirtualThreadCount()` — виртуальные потоки в
  очереди планировщика;
- `VirtualThreadSchedulerMXBean.getPoolSize()` — размер пула carrier-потоков;
- `VirtualThreadSchedulerMXBean.getParallelism()` — целевой parallelism планировщика;
- `ActiveRequests.current()` — количество chat-запросов, которые сейчас обрабатываются
  приложением (растёт вместе с нагрузкой и падает до нуля, когда нагрузка снята).

Важно: у виртуального потока нет собственного потока ОС. Поэтому «использование виртуальных
потоков» на графике — это `mounted` (сколько реально выполняется) и `queued` (сколько ждёт
carrier-поток). Платформенные потоки (включая carrier-потоки) показывает отдельная линия.

### Результат профилирования

Во внешнем режиме (по умолчанию) сэмплер работает внутри JVM приложения, поэтому в графике
только потоки приложения: Tomcat, Kafka listeners, HikariCP и т.д. Виртуальные потоки
генератора нагрузки в него не попадают. В каталоге `target/` появляются:

```
target/jmh-app-thread-usage-concurrency-100-<timestamp>.csv
target/jmh-app-thread-usage-concurrency-100-<timestamp>.svg
target/jmh-app-thread-usage-concurrency-100-<timestamp>.app.log
```

`.app.log` — stdout/stderr дочернего JVM приложения (полезен, если приложение не поднялось).
Пути к профилю и логу также печатаются в выводе JMH строкой `[external-app] ...`.

В in-process режиме (`BENCH_APP_MODE=inprocess`) профилировщик сэмплирует общую JVM, поэтому
в графике видны и потоки JMH-клиента:

```
target/jmh-thread-usage-thousandChatRequests-concurrency-100-<timestamp>.csv
target/jmh-thread-usage-thousandChatRequests-concurrency-100-<timestamp>.svg
```

- `.csv` — сырые сэмплы с колонками:
  `elapsed_ms`, `platform_threads`, `mounted_virtual_threads`, `queued_virtual_threads`,
  `carrier_pool_size`, `carrier_parallelism`, `active_requests`.
- `.svg` — готовый график, который можно открыть в браузере. На нём две панели с общей осью
  времени:
  - верхняя — потоки:
    - `Platform threads` — платформенные потоки;
    - `Mounted virtual threads` — виртуальные потоки на carrier-потоках;
    - `Queued virtual threads` — очередь виртуальных потоков;
    - `Carrier pool size` — размер пула carrier-потоков;
  - нижняя — `Active requests` — количество chat-запросов, которые сейчас обрабатываются;
  - серые/синие полосы — warmup- и measurement-итерации.

Незаштрихованные промежутки после measurement-итераций — это 10-секундные паузы без нагрузки
(`BENCH_IDLE_MILLIS`): на них `Active requests` падает до нуля, а потоки возвращаются к
базовому уровню.

Если запускается несколько значений `concurrency`, будет создано несколько пар файлов
(по одному на прогон). Примеры графиков лежат в `docs/report/`:

```
docs/report/jmh-app-thread-usage-concurrency-1-1794916670882.svg
docs/report/jmh-app-thread-usage-concurrency-10-1794916839133.svg
docs/report/jmh-app-thread-usage-concurrency-100-1794916927122.svg
```

Это реальный прогон во внешнем режиме с паузами по 10 секунд после каждой measurement-итерации:
видны полосы итераций, 10-секундные промежутки без нагрузки, падение `Active requests` до нуля
и возврат потоков к базовому уровню. Рядом с каждым `.svg` лежит соответствующий `.csv`.

### Более частое сэмплирование

По умолчанию интервал — 50 мс. Чтобы поймать короткие всплески, его можно уменьшить.

Во внешнем режиме интервал задаётся переменной `BENCH_PROFILE_INTERVAL_MS`:

```powershell
$env:BENCH_PROFILE_INTERVAL_MS="10"
mvn test-compile exec:exec "-Djmh.concurrency=100"
```

В in-process режиме используется опция JMH-профилировщика `interval=<мс>`:

```bash
mvn test-compile exec:exec "-Djmh.profiler=ru.maximserver.vthreads.ThreadUsageProfiler:interval=10"
```

> Чем меньше интервал, тем выше накладные расходы сэмплера.
> Для грубой оценки достаточно 50 мс, для детальной картины — 10–20 мс.

### Другие профилировщики

Свойство `jmh.profiler` принимает как класс собственного профилировщика, так и встроенные
JMH-профилировщики:

```bash
mvn test-compile exec:exec "-Djmh.profiler=gc"
mvn test-compile exec:exec "-Djmh.profiler=stack"
```

Во внешнем режиме сэмплер приложения запускается всегда, независимо от `jmh.profiler`;
смена профилировщика влияет только на JMH-часть (в частности, на разметку итераций).
В in-process режиме именно `jmh.profiler` отвечает за сэмплирование JVM.

Паузу после measurement-итераций выполняет собственный профилировщик `ThreadUsageProfiler`
(финальную паузу перед остановкой приложения — `ChatBenchmark`), поэтому при замене
`jmh.profiler` на встроенный промежуточные паузы не применяются.

Чтобы полностью отключить профилирование, уберите запуск `ThreadUsageRecorder` в
`ChatBenchmarkApp` (внешний режим) и/или аргументы `-prof` из конфигурации
`exec-maven-plugin` в `pom.xml` (in-process режим).

### Готовые схемы и графики

- `experiment-virtual-threads.svg` — схема эксперимента: взаимодействие REST/ChatService/Kafka,
  сравнение VT ON/OFF и схема проверки.
- `result-threads.svg` — наложение реальных профилей `platform threads` для VT ON и OFF.
- `docs/report/jmh-app-thread-usage-concurrency-*.svg` — реальные графики JMH (внешний режим,
  `concurrency` = 1, 10 и 100) с метриками потоков, количеством активных запросов и
  10-секундными паузами без нагрузки после каждой measurement-итерации.
- `docs/article/experiment-plan-uml.svg` — UML-диаграмма планируемого эксперимента
  (активности JMH-клиента и приложения, два режима VT, проверка нагрузки ≤ 2%).
- `docs/article/request-flow.svg` — схема обработки `POST /chat`: REST-контроллер, асинхронный
  вызов Kafka, блокирующее ожидание `future.get()` и возврат результата.
- `docs/article/vt-on.svg` и `docs/article/vt-off.svg` — прогоны с
  `VIRTUAL_THREADS_ENABLED=true/false`; `docs/article/vt-combined.svg` — совмещённый график
  обоих режимов вместе с нагрузкой.
- `docs/article/virtual-threads-spring-boot-jdk25-v2.md` — статья с разбором схемы запроса,
  результатами экспериментов и выводами о применении виртуальных потоков в Spring Boot Web
  на JDK 25.

## Как читать результаты

Итоговая таблица JMH выглядит примерно так (реальный прогон во внешнем режиме,
`BENCH_RESPONSE_DELAY=1ms`, JDK 25):

```
Benchmark                           (concurrency)  Mode  Cnt   Score   Error  Units
ChatBenchmark.thousandChatRequests              1    ss    3  23.747 ± 8.693  ms/op
ChatBenchmark.thousandChatRequests             10    ss    3   8.214 ± 2.428  ms/op
ChatBenchmark.thousandChatRequests            100    ss    3   8.042 ± 1.070  ms/op
```

Прогон выполнен с настройками по умолчанию (`VIRTUAL_THREADS_ENABLED=false`), поэтому на
графиках видно, что после снятия нагрузки `Active requests` падает до нуля, а платформенные
потоки Tomcat остаются в пуле. Чтобы увидеть, как высвобождаются именно виртуальные потоки,
запустите бенчмарк с `VIRTUAL_THREADS_ENABLED=true`.

- `Score` — среднее время одного запроса в миллисекундах;
- `Error` — погрешность измерения (чем меньше, тем стабильнее результат);
- `Cnt` — количество measurement-итераций;
- время всей пачки из 1000 запросов = `Score × 1000`.

Сравнивать имеет смысл прогоны с одинаковыми `jmh.*` настройками и одинаковой инфраструктурой.
Для более стабильных чисел увеличивайте `jmh.forks`, `jmh.warmupIterations` и
`jmh.measurementIterations`, а также закрывайте фоновые приложения.

## Эксперимент VT ON vs VT OFF

На той же инфраструктуре выполнен отдельный эксперимент: одинаковый профиль нагрузки
(1000 × `POST /chat` на итерацию, `concurrency=100`, `response-delay=1 ms`) прогнан дважды —
с `VIRTUAL_THREADS_ENABLED=true` и `false`. Сравниваются не разные нагрузки, а две модели
исполнения одного и того же сценария.

| Метрика | VT ON | VT OFF |
| --- | ---: | ---: |
| JMH Score | 7.951 ± 2.641 ms/op | 8.031 ± 3.070 ms/op |
| Средняя длительность measurement-итерации | 7.90 с | 7.98 с |
| Максимум активных запросов | 100 | 100 |
| Максимум платформенных потоков | 54 | 128 |
| Среднее платформенных потоков под нагрузкой | 53.6 | 127.6 |
| Mounted / queued VT (max) | 22 / 64 | 0 / 0 |
| Carrier pool (max) | 24 | 0 |

Нагрузка в прогонах отличается на 0.98% (в пределах 2%), Score — на 1.0%. При VT ON
100 одновременных запросов ждут ответа Kafka на виртуальных потоках: платформенных потоков
54 вместо 128, то есть в **2.37 раза меньше**.

Что это значит:

- виртуальные потоки дают выигрыш в экономии потоков ОС и масштабируемости по конкурентности,
  а не автоматическое ускорение latency;
- в этом сценарии узкое место — единственный Kafka-listener, поэтому Score почти не изменился;
- VT полезны для блокирующих I/O-вызовов (`CompletableFuture.get()`, JDBC, HTTP, Kafka);
- пул соединений БД и конкурентность Kafka listener-а остаются отдельными ограничителями.

Графики: `docs/article/vt-on.svg`, `docs/article/vt-off.svg`,
`docs/article/vt-combined.svg`; схема обработки запроса — `docs/article/request-flow.svg`.
Подробный разбор — в статье
[«Виртуальные потоки в Spring Boot Web на JDK 25»](docs/article/virtual-threads-spring-boot-jdk25-v2.md).

## Решение проблем

- **`docker compose` не запущен** — приложение и бенчмарк не смогут подключиться к
  PostgreSQL/Kafka. Проверьте `docker compose ps` и логи контейнеров.
- **Бенчмарк не найден** — классы ещё не скомпилированы. Используйте
  `mvn test-compile exec:exec`, а не просто `mvn exec:exec` после `mvn clean`.
- **Ошибка `No matching benchmarks`** — запрошено значение `concurrency`, которого нет в
  `@Param`. Доступны `1`, `10`, `100`.
- **Профилировщик не найден** — убедитесь, что `mvn test-compile` выполнен и `target/test-classes`
  присутствует в classpath (это делает конфигурация `exec-maven-plugin`).
- **PowerShell неверно разбирает аргументы** — заключайте `-D...` в кавычки, например
  `"-Djmh.concurrency=100"`.
- **Нет файлов профилирования** — во внешнем режиме ищите `target/jmh-app-thread-usage-*`
  и проверьте `.app.log`; путь печатается в строке `[external-app]`. В in-process режиме
  ищите `target/jmh-thread-usage-*`; если `jmh.profiler` переопределён на встроенный,
  CSV/SVG для in-process режима не создаются.
