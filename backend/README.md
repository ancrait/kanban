# Kanban backend

Стартовий Maven-проєкт Spring Boot для модульного моноліту з [Plan.md](../Plan.md). Є залежності, клас запуску `KanbanApplication` та налаштування в `application.properties`. Бізнес-коду, тестів, SQL-міграцій та реалізації API поки немає.

## Версії

| Компонент | Версія |
|---|---|
| Java target | 21 LTS |
| Spring Boot | 4.1.1 |
| Spring Modulith | 2.1.1 |
| springdoc OpenAPI | 3.1.1 |
| Maven | 3.10.0 через Maven Wrapper 3.3.4 |

Spring Boot і Spring Modulith взято із сумісного проєкту, згенерованого Spring Initializr. Версії решти залежностей керуються Spring Boot parent і Spring Modulith BOM.

Maven-координати: `com.kanban:kanban-backend:0.0.1-SNAPSHOT`. Базовий Java-пакет: `com.kanban.backend`.

## Підключені залежності

| Напрям | Залежності |
|---|---|
| REST | Spring Web MVC, embedded Tomcat |
| Валідація | Spring Validation |
| Безпека | Spring Security, OAuth2 Resource Server/JWT, Security Messaging для STOMP |
| Дані | Spring Data JPA, PostgreSQL JDBC Driver |
| Міграції | Spring Boot Flyway starter + PostgreSQL-модуль Flyway |
| Модульність і події | Spring Modulith JDBC starter, Event Publication Registry, Insight |
| Realtime | Spring WebSocket/STOMP |
| OpenAPI | springdoc WebMVC UI / Swagger UI |
| Метрики | Actuator, Micrometer Prometheus registry |
| Тестування | Spring Boot test starters для підключених компонентів, Spring Modulith Test, Testcontainers JUnit Jupiter і PostgreSQL, Spring Boot Testcontainers |

JWT-підтримка використовує Spring Security JOSE/Nimbus; окрема JWT-бібліотека для цього каркаса не потрібна. BCrypt доступний через Spring Security.

Для Redis підготовлено вимкнений за замовчуванням Maven-профіль `redis`: Spring Data Redis, Cache та Redis test starter. Він підключається через `-Predis`, коли виникне відповідна задача. Профіль додає бібліотеки, але не запускає Redis і не налаштовує кеш самостійно.

## Структура

```text
backend/
  .mvn/wrapper/maven-wrapper.properties
  mvnw
  mvnw.cmd
  pom.xml
  src/main/java/com/kanban/backend/
    KanbanApplication.java
  src/main/resources/
    application.properties
```

Модулі `auth`, `board`, `activity`, `notification`, тести та міграції додаватимемо разом із відповідними файлами. `.gitkeep` видалені: це звичайні порожні файли для збереження порожніх директорій у Git, вони не є частиною Spring Boot. Архітектурну перевірку модулів ще потрібно реалізувати.

## Налаштування

Spring Boot автоматично читає `src/main/resources/application.properties`; файл `app.properties` самостійно він не підхоплює. YAML-конфігурації застосунку більше немає. OpenAPI YAML у `docs/api/` залишається специфікацією HTTP API.

| Змінна середовища | Значення за замовчуванням |
|---|---|
| `SERVER_PORT` | `8080` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/kanban` |
| `DB_USERNAME` | `kanban` |
| `DB_PASSWORD` | Порожній рядок; для БД із парольним доступом потрібно вказати пароль |

Потрібні запущений PostgreSQL, створена БД і користувач із правами на неї. Пароль задається в середовищі процесу, а не комітиться у файл. `.env` самостійно Spring Boot не завантажує.

Hibernate налаштовано на `ddl-auto=validate`, Open Session in View вимкнено. Папка майбутніх Flyway-міграцій — `src/main/resources/db/migration`. SQL-міграцій поки немає; технічний журнал подій наразі ініціалізується стандартним механізмом Spring Modulith. Керування його схемою через Flyway належить до подальшої задачі з подіями.

## Відкрити та перевірити

В IntelliJ IDEA відкрий `backend/pom.xml` як Maven-проєкт, обери JDK 21 або сумісний новіший JDK і виконай Reload Maven Project. Maven Wrapper завантажить зафіксовану версію Maven; окреме встановлення Maven не потрібне.

PowerShell, із директорії `backend`:

```powershell
.\mvnw.cmd --version
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Для термінала потрібен JDK і коректний `JAVA_HOME`. Якщо JDK є лише в налаштуваннях IDEA, налаштуй також Maven Runner JRE або `JAVA_HOME` у поточному терміналі. На Linux/macOS використовуй `./mvnw` після надання йому права на виконання за потреби.

Перевірка з опціональними залежностями Redis:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -Predis verify
```

`verify` компілює стартовий клас і створює виконуваний Spring Boot JAR. Бізнес-тести ще відсутні; сама збірка не потребує запущеної БД. Для запуску застосунку PostgreSQL потрібен.

## Запуск

В IntelliJ IDEA відкрий `KanbanApplication.java`, натисни зелений трикутник біля `main` і вибери **Run 'KanbanApplication'**. У **Run → Edit Configurations → Environment variables** задай `DB_PASSWORD`, а за потреби — `DB_URL` і `DB_USERNAME`.

Або з PowerShell у директорії `backend`, після підготовки БД:

```powershell
$env:DB_PASSWORD = '<пароль БД>'
.\mvnw.cmd spring-boot:run
```

Зібраний JAR можна запустити з тими самими змінними середовища:

```powershell
java -jar target\kanban-backend-0.0.1-SNAPSHOT.jar
```

Перевірка стану — [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health). Бізнес-ендпоінтів поки немає. До реалізації auth діє стандартна конфігурація Spring Security; тимчасовий пароль користувача `user` виводиться під час старту.

Перевірено `clean verify` та запуск зібраного JAR із тимчасовим PostgreSQL 17: `/actuator/health` повернув HTTP 200 зі статусом `UP`. Використано локальний JDK 24.0.1 із Java target 21; тестовий процес і контейнер після перевірки зупинено, контейнер видалено.

## Що додати наступним

1. PostgreSQL у Compose та початкові Flyway-міграції.
2. Перевірку меж модулів, конфігурацію безпеки та реалізацію auth за погодженим API.

## Джерела

- [Spring Initializr](https://start.spring.io/) — вихідний Maven-каркас і wrapper.
- [Spring Modulith](https://docs.spring.io/spring-modulith/reference/index.html) — BOM і модулі.
- [springdoc](https://springdoc.org/) — OpenAPI/Swagger UI для Spring Boot 4.
- [Spring Boot Maven Plugin](https://docs.spring.io/spring-boot/maven-plugin/packaging.html) — executable packaging.
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) — `application.properties` і змінні середовища.
