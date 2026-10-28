# Kanban backend

Maven-проєкт Spring Boot для модульного моноліту з [Plan.md](../Plan.md). Є PostgreSQL, JPA-сутності й репозиторії auth, Flyway, Spring Security, JWT, CSRF endpoint, реєстрація, вхід, профіль поточного користувача, ротація refresh-токенів, HTTP logout, планове очищення завершених сесій та локальний rate limit. Браузерна інтеграція й CI ще попереду.

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
| Скорочення шаблонного коду | Lombok: getters і protected no-args конструктори JPA-сутностей; версія керується Spring Boot |
| Безпека | Spring Security, OAuth2 Resource Server/JWT, Security Messaging для STOMP |
| Дані | Spring Data JPA, PostgreSQL JDBC Driver |
| Міграції | Spring Boot Flyway starter + PostgreSQL-модуль Flyway |
| Модульність і події | Spring Modulith JDBC starter, Event Publication Registry, Insight |
| Realtime | Spring WebSocket/STOMP |
| OpenAPI | springdoc WebMVC UI / Swagger UI |
| Метрики | Actuator, Micrometer Prometheus registry |
| Тестування | Spring Boot test starters для підключених компонентів, Spring Modulith Test, Testcontainers JUnit Jupiter і PostgreSQL, Spring Boot Testcontainers |

JWT-підтримка використовує Spring Security JOSE/Nimbus; окрема JWT-бібліотека для цього каркаса не потрібна. BCrypt доступний через Spring Security.

Lombok підключено як optional-залежність і виключено з виконуваного JAR. У Maven явно ввімкнено annotation processing (`proc=full`), що також зберігає роботу наявного процесора Spring Modulith. Конструктори з обов'язковими параметрами та методи зміни стану auth залишаються явними; Lombok генерує `@Getter` і `@NoArgsConstructor(access = PROTECTED)`.

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
    auth/
      package-info.java
      internal/domain/
        User.java
        RefreshSession.java
        RefreshToken.java
      internal/application/
        AuthService.java
        AuthRateLimiter.java
        RateLimitExceededException.java
        RefreshSessionCleanup.java
        EmailAlreadyExistsException.java
        InvalidCredentialsException.java
        InvalidAccessTokenException.java
        InvalidRefreshTokenException.java
      internal/persistence/
        UserRepository.java
        RefreshSessionRepository.java
        RefreshTokenRepository.java
      internal/config/
        AuthProperties.java
        AuthRateLimitProperties.java
        AuthConfig.java
      internal/security/
        SecurityConfig.java
        SecurityErrorHandler.java
        JwtConfig.java
        JwtService.java
        RefreshTokenService.java
      internal/web/
        CsrfController.java
        AuthController.java
        UserController.java
    infrastructure/
      package-info.java
      web/
        package-info.java
        ApiExceptionHandler.java
        ApiErrorResponse.java
        ApiValidationErrorResponse.java
        ApiJsonConfig.java
  src/main/resources/
    application.properties
    db/migration/V1__init_auth_tables.sql
  src/test/java/com/kanban/backend/
    ApiExceptionHandlerTest.java
    ModularityTest.java
    auth/AuthPersistenceTest.java
    auth/SecurityConfigTest.java
    auth/SecurityApplicationTest.java
    auth/RegistrationTest.java
    auth/LoginTest.java
    auth/RefreshTest.java
    auth/RefreshSessionCleanupTest.java
    auth/AuthRateLimiterTest.java
    auth/AuthRateLimitTest.java
```

Модуль `auth` містить користувачів, refresh-сесії та історію їх токенів. Сутності внутрішні для модуля; зв'язки зберігаються як UUID, foreign key визначені в SQL усередині схеми `auth`. Архітектурний тест перевіряє межі через Spring Modulith. Модулі `board`, `activity`, `notification` додаватимемо з відповідною поведінкою.

`UserRepository.findByEmail()` очікує вже нормалізований email. `RefreshTokenRepository.findByTokenHash()` повертає також спожиті й прострочені токени для replay detection. Перед блокуванням `findSessionIdByTokenHash()` читає лише UUID сесії, тому старий стан токена не потрапляє в JPA persistence context. `RefreshSessionRepository.findByIdForUpdate()` використовує `PESSIMISTIC_WRITE`; після отримання блокування сервіс читає актуальний токен. Блокування звільняється після завершення транзакції.

`V1__init_auth_tables.sql` створює схему `auth`, таблиці `users`, `refresh_sessions`, `refresh_tokens`, індекси та constraints. Refresh-хеш має формат SHA-256 у lowercase hex (64 символи); секрети токенів у таблиці не зберігаються. Partial UNIQUE index дозволяє лише один невикористаний токен на сесію. Під час ротації сервіс зберігає `consumed_at` старого токена через `flush()` до INSERT наступного. Історія токенів видаляється каскадно при очищенні сесії; сама ротація та відкликання її не видаляють.

## Налаштування

Spring Boot автоматично читає `src/main/resources/application.properties`; файл `app.properties` самостійно він не підхоплює. YAML-конфігурації застосунку більше немає. OpenAPI YAML у `docs/api/` залишається специфікацією HTTP API.

| Змінна середовища | Призначення |
|---|---|
| `SERVER_PORT` | Порт backend, за замовчуванням `8080` |
| `POSTGRES_HOST` | Хост БД; для backend з IDE й локального Compose — `localhost` |
| `POSTGRES_PORT` | Порт БД, наприклад `5432` |
| `POSTGRES_DB` | Назва БД, наприклад `kanban` |
| `POSTGRES_USER` | Користувач БД, наприклад `kanban` |
| `POSTGRES_PASSWORD` | Пароль БД |

Потрібні запущений PostgreSQL, створена БД і користувач із правами на неї. Для локального запуску є [Compose](../docker/docker-compose.yml) і [приклад змінних](../docker/.env.example). Усі `POSTGRES_*` для backend потрібно задати в середовищі його процесу; пароль не комітиться. `docker/.env.example` містить приклади також для auth. Spring Boot самостійно не завантажує `docker/.env`: для запуску з IntelliJ скопіюй потрібні змінні в **Run → Edit Configurations → Environment variables**.

Hibernate налаштовано на `ddl-auto=validate`, Open Session in View вимкнено. Flyway застосовує міграції з `src/main/resources/db/migration` до Hibernate validation. Міграція auth не створює технічні таблиці журналу подій Spring Modulith; керування ними належить до подальшої задачі з подіями.

### Налаштування auth

`AuthConfig` реєструє `AuthProperties` з префіксом `app.auth`, `AuthRateLimitProperties` з префіксом `app.auth.rate-limit`, UTC `Clock`, `BCryptPasswordEncoder` та вмикає Spring scheduling. JWT, очищення сесій і limiter використовують цей годинник.

| Property | Змінна середовища | Значення за замовчуванням |
|---|---|---|
| `app.auth.access-ttl` | `JWT_ACCESS_EXPIRATION` | `15m` |
| `app.auth.refresh-ttl` | `JWT_REFRESH_EXPIRATION` | `7d` |
| `app.auth.session-retention` | `AUTH_SESSION_RETENTION` | `7d` після завершення сесії |
| `app.auth.cleanup-interval` | `AUTH_CLEANUP_INTERVAL` | `1h` (також затримка першого запуску) |
| `app.auth.cleanup-batch-size` | `AUTH_CLEANUP_BATCH_SIZE` | `500` (допустимо 1–1000) |
| `app.auth.issuer` | `JWT_ISSUER` | `kanban-backend` |
| `app.auth.audience` | `JWT_AUDIENCE` | `kanban-api` |
| `app.auth.private-key-location` | `JWT_PRIVATE_KEY_LOCATION` | Обов'язковий шлях до приватного RSA-ключа |
| `app.auth.public-key-location` | `JWT_PUBLIC_KEY_LOCATION` | Обов'язковий шлях до публічного RSA-ключа |
| `app.auth.clock-skew` | `JWT_CLOCK_SKEW` | `30s` |
| `app.auth.cookie-secure` | `APP_AUTH_COOKIE_SECURE` | `true` |
| `app.auth.bcrypt-strength` | `BCRYPT_STRENGTH` | `12` (допустимо 4–31) |

TTL і clock skew мають тип `Duration`: підтримуються значення `15m`, `7d`, `30s` та ISO-8601. Числа без одиниці трактуються як секунди. TTL має бути не менше секунди, clock skew — невід'ємним. Issuer, audience та обидва шляхи до ключів обов'язкові й не можуть бути порожніми. Для шляхів потрібен префікс `file:` або `classpath:`, наприклад `file:C:/kanban-secrets/access-private.pem`. Приватні ключі зберігай поза Git.

Binding і перевірки виконуються при створенні `AuthProperties`; некоректна конфігурація зупиняє запуск. `JwtConfig` читає приватний ключ PKCS#8 PEM і публічний X.509 PEM, перевіряє відповідність пари та розмір від 2048 біт. Підпис і перевірка — лише RS256. Обов'язкові claims: `iss`, `aud`, UUID у `sub`, `iat`, `exp`; `nbf` перевіряється, якщо присутній. `RefreshTokenService` створює opaque-токен із 32 випадкових байтів у Base64URL без padding; у БД зберігається лише SHA-256 хеш.

Налаштування auth містяться в `application.properties`; локальні приклади — у `docker/.env.example`. Для HTTP задай `APP_AUTH_COOKIE_SECURE=false`; production використовує HTTPS і значення `true`. Вартість BCrypt за замовчуванням 12, її потрібно виміряти на цільовому сервері перед релізом.

Створення локальної пари ключів у **PowerShell 7** (виконати один раз у новій директорії; наявні ключі не перезаписувати):

```powershell
$keyDirectory = 'C:/kanban-secrets'
New-Item -ItemType Directory -Path $keyDirectory -ErrorAction Stop | Out-Null
$rsa = [System.Security.Cryptography.RSA]::Create(2048)
[IO.File]::WriteAllText("$keyDirectory/access-private.pem", $rsa.ExportPkcs8PrivateKeyPem())
[IO.File]::WriteAllText("$keyDirectory/access-public.pem", $rsa.ExportSubjectPublicKeyInfoPem())
$rsa.Dispose()
```

## Spring Security

Два `SecurityFilterChain`: `/api/v1/auth/**` використовує CSRF cookie, решта `/api/v1/**` вимагає Bearer JWT. Auth POST register/login/refresh/logout пропускаються лише з чинним CSRF; access-токен на цих маршрутах не перевіряється. Обидва ланцюжки stateless; form login, HTTP Basic, стандартний logout і request cache вимкнені. Cookie refresh, query parameter і HTTP session не автентифікують API.

`GET /api/v1/auth/csrf` уже працює. Відповідь: `{ "token": "...", "headerName": "X-CSRF-TOKEN" }`; cookie `kanban_csrf` має `HttpOnly`, `SameSite=Lax`, `Path=/api/v1/auth`, без `Domain`. Значення в JSON замасковане стандартним XOR handler Spring: надсилай саме його в `X-CSRF-TOKEN`, а cookie — через `credentials: include`/Vite proxy. Після успішної реєстрації, входу або logout CSRF cookie видаляється; перед наступним auth POST отримай новий токен через GET. Refresh CSRF не скидає.

Security filters повертають `ApiErrorResponse`: `401 ACCESS_TOKEN_MISSING/INVALID/EXPIRED` з `WWW-Authenticate: Bearer`, `403 CSRF_INVALID` або `ACCESS_DENIED`. Відповіді мають `Cache-Control: no-store`. Використання DTO з `infrastructure :: web` явно дозволено для auth.

Публічний лише мінімальний `GET /actuator/health`. Інші маршрути поза API заборонені. Swagger і `/v3/api-docs` увімкнені й доступні лише з профілем `dev` (`SPRING_PROFILES_ACTIVE=dev`).

## Реєстрація

`POST /api/v1/auth/register` приймає `{email, password, displayName}` з CSRF cookie та заголовком. `RegisterRequest` нормалізує email та ім'я й перевіряється через `@Valid`. `AuthService.register()` хешує пароль BCrypt і в одній транзакції створює користувача, refresh-сесію та хеш початкового refresh-токена. Збій будь-якого запису відкочує всю реєстрацію. UNIQUE `uk_users_email` захищає також від паралельних запитів; лише його порушення перетворюється на `409 EMAIL_ALREADY_EXISTS`.

Успіх — `201 AuthResponse` (`accessToken`, `tokenType`, `expiresIn`, `user`), `Location: /api/v1/users/me`, `Cache-Control: no-store`. `AuthController` встановлює `kanban_refresh`: HttpOnly, SameSite=Lax, Path=/api/v1/auth, без Domain, Secure за конфігурацією, Max-Age за refresh TTL (типово 604800 секунд). Refresh і password hash відсутні у JSON. Видалення CSRF та встановлення refresh повертаються окремими `Set-Cookie` headers, після успішного завершення транзакції. `createdAt` користувача перед збереженням має точність мікросекунд, як у PostgreSQL, тому збігається у відповіді реєстрації та профілю.

## Вхід

`POST /api/v1/auth/login` приймає `{email, password}` із чинним CSRF. `LoginRequest` нормалізує email, але зберігає пароль без змін. `AuthService.login()` знаходить користувача та викликає `PasswordEncoder.matches()`. Для невідомого email перевіряється підставний BCrypt-хеш, створений один раз при запуску з поточною вартістю BCrypt. Невідомий email і неправильний пароль повертають однакову `401 INVALID_CREDENTIALS` з повідомленням «Неправильний email або пароль.», без зміни cookie чи сесій.

Успіх — `200 AuthResponse`, нова незалежна refresh-сесія, refresh cookie та видалення CSRF cookie. Попередні сесії залишаються чинними. Приватні `AuthService.createSession()` і `AuthController.setAuthCookies()` використовуються і реєстрацією, і входом. Створення сесії та хешу токена транзакційне; cookie видаються після коміту. Bearer на auth-маршрутах не потрібен. Мінімальна довжина нового пароля з реєстрації при вході не перевіряється; залишаються обов'язковість і максимум 72 UTF-8 байти.

## Оновлення токенів

`POST /api/v1/auth/refresh` приймає refresh лише з `kanban_refresh` cookie, без body та без вимоги чинного access JWT. Потрібні CSRF cookie і заголовок із `GET /auth/csrf`. `AuthService.refresh()` у транзакції знаходить сесію за SHA-256 хешем, блокує її рядок, читає токен і перевіряє стан. Чинний токен позначається використаним, його UPDATE виконується через `flush()`, далі записується новий хеш у тій самій сесії. Строк сесії та нового токена продовжується на refresh TTL від поточного часу.

Успіх — `200 AuthResponse` та нова HttpOnly refresh cookie після коміту; CSRF залишається придатним. Missing/unknown/expired/revoked/reused refresh → `401 REFRESH_INVALID` і видалення refresh cookie (`Max-Age=0`, ті самі Path, HttpOnly, Secure та SameSite). Невалідний CSRF дає `403` до виконання операції; серверна помилка дає `500` без заміни cookie та з відкатом транзакції.

Повторне використання спожитого токена відкликає всю його refresh-сесію, зокрема коли власний TTL старого токена минув, а сесія активна. Сервіс повертає порожній `Optional`, щоб відкликання закомітилося; контролер формує `401` після завершення транзакції. Інші сесії користувача не відкликаються, видані access JWT діють до закінчення TTL. Для двох одночасних refresh одним токеном очікується `200` та `401`, після чого новий refresh із першої відповіді теж непридатний. Frontend має координувати refresh між запитами й вкладками.

## Вихід

`AuthService.logout(String refreshToken)` відкликає відповідну refresh-сесію в транзакції. За SHA-256 хешем знаходить UUID сесії, отримує те саме `PESSIMISTIC_WRITE` блокування, що refresh, і викликає `RefreshSession.revoke()`. Використаний чи прострочений історичний токен також може відкликати свою сім'ю. Порожній/невідомий токен або вже видалена сесія не спричиняють помилку; повторний logout не змінює початковий `revokedAt`. Історія токенів та інші сесії зберігаються, access JWT залишається чинним до expiry.

`POST /api/v1/auth/logout` потребує чинних CSRF cookie/header, але не access JWT. Контролер бере refresh лише з cookie, викликає сервіс і після успішного завершення транзакції видаляє `kanban_refresh` та `kanban_csrf` окремими `Set-Cookie` headers: порожнє значення, Max-Age=0, той самий Path=/api/v1/auth, HttpOnly, SameSite=Lax та налаштований Secure. Відповідь — `204` без тіла, `Cache-Control: no-store`. Відсутня/невідома/відкликана refresh cookie також дає `204`; неправильний CSRF повертає `403` до відкликання і не видаляє refresh cookie. Перед наступним auth POST отримай новий CSRF через GET.

## Обмеження частоти auth-запитів

`AuthController` перевіряє `AuthRateLimiter` після CSRF і валідації DTO, перед викликом транзакційного `AuthService`, BCrypt і запитами до БД. Для login потрібні вільні ліміти за IP та нормалізованим email одночасно; правило однакове для наявних і невідомих користувачів. Успішні й невдалі спроби враховуються. Помилки CSRF, malformed JSON і валідації DTO не витрачають квоту.

| Property `app.auth.rate-limit.*` | Змінна середовища | За замовчуванням |
|---|---|---|
| `register-attempts` | `AUTH_RATE_LIMIT_REGISTER_ATTEMPTS` | 5 на IP |
| `register-window` | `AUTH_RATE_LIMIT_REGISTER_WINDOW` | `1h` |
| `login-ip-attempts` | `AUTH_RATE_LIMIT_LOGIN_IP_ATTEMPTS` | 30 на IP |
| `login-email-attempts` | `AUTH_RATE_LIMIT_LOGIN_EMAIL_ATTEMPTS` | 10 на email, незалежно від IP |
| `login-window` | `AUTH_RATE_LIMIT_LOGIN_WINDOW` | `15m` |
| `refresh-attempts` | `AUTH_RATE_LIMIT_REFRESH_ATTEMPTS` | 120 на IP |
| `refresh-window` | `AUTH_RATE_LIMIT_REFRESH_WINDOW` | `1m` |
| `max-entries` | `AUTH_RATE_LIMIT_MAX_ENTRIES` | 10000 записів, допустимо 2–100000 |

Вікно починається з першої дозволеної спроби; відхилені запити його не продовжують. Лічильники потокобезпечні, прострочені записи очищаються перед перевіркою. При заповненні пам'яті нові ключі отримують `429`, активні лічильники не витісняються. Вікна мають бути від секунди; число без одиниці — секунди; кількість спроб — від 1.

Відмова — `429 RATE_LIMITED`, JSON `ApiErrorResponse`, `Cache-Control: no-store`, `Retry-After` у цілих секундах з округленням угору. Cookie не змінюються, refresh не споживається й replay не обробляється до проходження ліміту. Logout залишається доступним із чинним CSRF. Клієнт має зачекати `Retry-After` перед повтором.

Використовується адреса з'єднання `request.getRemoteAddr()`. `server.forward-headers-strategy=none` вимикає довіру до довільних `Forwarded`/`X-Forwarded-For`; перед деплоєм за reverse proxy потрібно окремо налаштувати довірений proxy. Інакше клієнти за ним матимуть спільний IP-ліміт. Лічильники локальні й скидаються при рестарті; для кількох інстансів потрібен спільний limiter. Redis для поточного запуску не потрібен.

## Очищення завершених сесій

Погоджений строк зберігання — **7 днів після закінчення строку дії або відкликання**, залежно від того, що сталося раніше. `RefreshSessionCleanup.deleteCompletedSessions()` запускається з fixed delay щогодини; перший запуск — через годину після старту застосунку. За одну транзакцію видаляється до 500 сесій, для яких `expires_at <= now - retention` або `revoked_at <= now - retention`. Токени цих сесій видаляються наявним FK `ON DELETE CASCADE`; користувачі зберігаються.

Активні сесії та вся їх історія залишаються, включно зі старими спожитими токенами з власним простроченим TTL: вони потрібні для replay detection. SQL використовує `FOR UPDATE SKIP LOCKED`, тому рядки, заблоковані refresh/logout або іншим запуском cleanup, пропускаються до наступного запуску. Велика черга очищається порціями в наступних запусках; фактичне зберігання може бути довшим за 7 днів через інтервал, ліміт порції чи блокування. HTTP-контракт не змінюється.

Retention, інтервал і розмір порції задаються через `app.auth` та відповідні змінні середовища в таблиці вище. Retention та інтервал підтримують `7d`, `1h`, ISO-8601; числа без одиниць означають секунди і для binding, і для планувальника. Retention невід'ємний, інтервал — не менше секунди; розклад працює лише доки backend запущений. Окремі зовнішні cron, Redis чи бібліотеки для цього не потрібні.

## Поточний користувач

`GET /api/v1/users/me` приймає `Authorization: Bearer <accessToken>`. `UserController.me()` бере UUID із перевіреного JWT, а `AuthService.getCurrentUser()` читає користувача з БД у read-only транзакції та повертає `UserResponse`: `id`, `email`, `displayName`, `createdAt`. Відповідь — `200` із `Cache-Control: no-store`, без password hash, токенів, нових cookie чи HTTP session.

Якщо користувача з таким UUID уже немає, `InvalidAccessTokenException` перетворюється на `401 ACCESS_TOKEN_INVALID` з `WWW-Authenticate: Bearer`. Відсутній або некоректний JWT обробляють наявні security filters; refresh cookie сама по собі не дає доступу до профілю.

## Обробка помилок API

`infrastructure.web.ApiExceptionHandler` — звичайний `@RestControllerAdvice` із трьома `@ExceptionHandler`: для `@Valid` request body, некоректного запиту та решти винятків. Помилки полів повертаються як `Map<String, String>` із повідомлень анотацій DTO. `ApiJsonConfig` відхиляє невідомі JSON-поля, зайвий JSON і неправильні типи.

`ApiErrorResponse` містить `status`, `error`, `code`, `message`, `timestamp`; `ApiValidationErrorResponse` має `errors` замість `message`. Відповіді — `application/json` із `Cache-Control: no-store`. Статуси MVC й заголовки на кшталт `Allow` збережено. Непередбачені винятки повертають безпечний `500 INTERNAL_ERROR`; у лог потрапляє лише тип винятку.

DTO використовують готові `@NotBlank`, `@Email`, `@Size` та `@CodePointLength` із Hibernate Validator. Власних анотацій і класів валідаторів немає. Unicode-довжина імені узгоджена з PostgreSQL; межа 72 UTF-8 байти BCrypt перевіряється короткою умовою в конструкторі request DTO й повертає `400 INVALID_REQUEST`. Для входу перевіряємо наявність пароля та межу BCrypt, без мінімальної довжини нового пароля з реєстрації.

Writer і `ProblemDetail` прибрано. Security handlers і limiter використовують ті самі DTO, доступ із auth до `infrastructure :: web` явно дозволений. Паролі, токени, повідомлення непередбачених винятків і stack trace не повертаються клієнту.

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

`verify` компілює застосунок, запускає тести й створює виконуваний Spring Boot JAR. Для тестів потрібен запущений Docker: Testcontainers сам створює ізольований PostgreSQL 16, застосовує Flyway й перевіряє JPA mapping та SQL constraints. Локальна БД Compose у тестах не використовується. `ModularityTest` перевіряє архітектурні межі.

## Запуск

В IntelliJ IDEA відкрий `KanbanApplication.java`, натисни зелений трикутник біля `main` і вибери **Run 'KanbanApplication'**. У **Run → Edit Configurations → Environment variables** задай всі `POSTGRES_*` та обидва `JWT_*_KEY_LOCATION` з таблиць вище. Для локального HTTP додай `APP_AUTH_COOKIE_SECURE=false`.

Або з PowerShell у директорії `backend`, після підготовки БД:

```powershell
$env:POSTGRES_HOST = 'localhost'
$env:POSTGRES_PORT = '5432'
$env:POSTGRES_DB = 'kanban'
$env:POSTGRES_USER = 'kanban'
$env:POSTGRES_PASSWORD = '<пароль БД>'
$env:JWT_PRIVATE_KEY_LOCATION = 'file:C:/kanban-secrets/access-private.pem'
$env:JWT_PUBLIC_KEY_LOCATION = 'file:C:/kanban-secrets/access-public.pem'
$env:APP_AUTH_COOKIE_SECURE = 'false'
.\mvnw.cmd spring-boot:run
```

Зібраний JAR можна запустити з тими самими змінними середовища:

```powershell
java -jar target\kanban-backend-0.0.1-SNAPSHOT.jar
```

Перевірка стану — [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health). Є `GET /api/v1/auth/csrf`; бізнес-ендпоінти register/login/refresh/logout/me ще не реалізовані. Правила Security для них уже налаштовані.

2026-10-24 перевірено `.\mvnw.cmd --batch-mode --no-transfer-progress verify` на JDK 22.0.2 із Java target 21: 24 тести пройшли без помилок і пропусків, JAR зібрано. PostgreSQL 16.14 у Testcontainers: міграція на чистій БД і її повторний запуск, Hibernate validation, round-trip усіх сутностей, Unicode-ліміт імені, UNIQUE/CHECK/FK constraints, історія ротації, каскадне очищення сесії та пошук через репозиторії. Окремі транзакції перевіряють конкуренцію за блокування сесії й можливість отримати його після завершення першої транзакції. Повний запуск HTTP-застосунку після цих змін окремо не перевірявся.

2026-10-25 додано `AuthProperties`, `AuthConfig` та UTC `Clock`. Виконано `.\mvnw.cmd --batch-mode --no-transfer-progress compile`: компіляція пройшла. Після цієї зміни тести та завантаження Spring-контексту окремо не запускалися. Налаштування cookie зведено до `application.properties`: `APP_AUTH_COOKIE_SECURE` перевизначає значення за замовчуванням `true`; окремий локальний файл прибрано.

2026-10-25 після переписування обробника у стилі звичайного `@RestControllerAdvice` виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: 43 тести без помилок і пропусків, JAR зібрано. Із них 19 перевірок помилок і DTO, 23 persistence-перевірки на PostgreSQL Testcontainers і перевірка меж модулів. Перевірено новий JSON-формат, повідомлення полів, required-поля, некоректний JSON і типи, невідомі поля, параметри контролера, безпечні `500` та логи, статуси `404`/`405`/`415`, `Allow`, Unicode-довжину, нормалізацію та межу BCrypt. Security filters у тестовому MVC-контексті вимкнені; повний HTTP auth ще не реалізовано. Контракт оновлено до `0.1.0-draft.2`.

2026-10-26 додано Spring Security, BCrypt і JWT RS256. Виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: 77 тестів без помилок і пропусків, JAR зібрано. Нові 33 перевірки Security проходять із реальними RSA-підписами, PEM-файлами й CSRF token/cookie; перевірено claims, строки дії, алгоритм і ключ підпису, stateless, JSON `401`/`403`, fallback і налаштування cookie. Окремий тест запускає повний застосунок із PostgreSQL Testcontainers, Flyway, Hibernate, RSA-ключами та реальними health/CSRF endpoint. Тестові ключі генеруються в `target/`, видаляються після тестів і не потрапляють у JAR. Browser-flow і register/login/refresh/logout/me ще попереду.

2026-10-26 після додавання реєстрації виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: **90 тестів без помилок і пропусків**, JAR зібрано. `RegistrationTest` містить 12 перевірок із PostgreSQL Testcontainers: успішна реєстрація й нормалізація, BCrypt, SHA-256 refresh-хеш, TTL, точний JSON, cookie/CSRF, дублікат і конкурентна реєстрація, валідація, rollback, інші помилки БД. `SecurityApplicationTest` додатково перевіряє Secure cookie за замовчуванням. Перевірки виконані через MockMvc з реальними security filters; фінальна перевірка з frontend у браузері ще потрібна.

2026-10-26 після додавання логіну виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: **101 тест без помилок і пропусків**, JAR зібрано. Новий `LoginTest` містить 12 перевірок: успіх із нормалізацією email, JWT/профіль/refresh-хеш/TTL/cookie, однакова помилка та перевірка BCrypt для невідомого email, незалежні сесії, незмінений пароль і короткий чинний пароль, валідація й байтова межа, CSRF, відсутність залежності від Bearer та rollback без втрати попередньої сесії. Browser-flow ще не перевірено.

2026-10-26 після додавання `/users/me` виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: **106 тестів без помилок і пропусків**, JAR зібрано. `SecurityApplicationTest` тепер містить 6 тестів із PostgreSQL та реальними JWT/CSRF: запуск застосунку, профілі двох користувачів через register/login, видалений та невідомий користувач, refresh cookie без Bearer, пошкоджений JWT. Перевірено точний DTO, no-store, `WWW-Authenticate`, відсутність секретів та нових cookie/session. Наскрізний тест використовує системний час і перевіряє збіг `createdAt` між реєстрацією та читанням із PostgreSQL. Фінальна браузерна перевірка ще потрібна.

2026-10-28 після додавання refresh виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: **122 тести без помилок і пропусків**, JAR зібрано. `RefreshTest` додає 16 перевірок на PostgreSQL Testcontainers: ротація й sliding TTL, прострочений Bearer, SHA-256 і cookie, missing/unknown/expired/revoked refresh, replay та незалежність інших сесій, replay простроченого спожитого токена, CSRF до будь-якої зміни стану, cookie як єдине джерело refresh, видалений користувач, два одночасні запити. Реальний UNIQUE-конфлікт нового хешу на коміті підтверджує rollback споживання старого токена і зміни сесії; повтор із тим самим старим токеном після збою успішний. Concurrency-тест синхронізує запити перед реальним блокуванням PostgreSQL і перевіряє `200`/`401` та збережене відкликання. Фінальна браузерна перевірка ще потрібна.

2026-10-28 після додавання сервісу logout виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: **129 тестів без помилок і пропусків**, JAR зібрано. Додано 7 перевірок у `RefreshTest` (тепер 23): відкликання лише поточної сесії, незмінний час повторного відкликання, чинність іншої сесії й access JWT, вихід зі старим спожитим/простроченим токеном, null/empty/unknown та одночасні logout/refresh. Перевірки використовують PostgreSQL Testcontainers і справжні транзакції сервісу. HTTP logout та cookie-очищення ще попереду.

2026-10-28 після підключення HTTP logout виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: **138 тестів без помилок і пропусків**, JAR зібрано. Додано 9 перевірок у `RefreshTest` (тепер 32): `204` без тіла, обидві cookie з Max-Age=0 та правильними атрибутами, no-store, відкликання лише поточної сесії, missing/empty/unknown refresh, повторний logout із новим CSRF і наступний login, неправильний/відсутній CSRF, прострочений Bearer. Фінальна браузерна перевірка ще потрібна.

2026-10-28 після додавання очищення завершених сесій виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: **145 тестів без помилок і пропусків**, JAR зібрано. Шість PostgreSQL-тестів перевіряють retention, межі часу, каскад токенів, збереження активної історії, порції й пропуск заблокованих сесій. Окремий тест підтверджує реальну реєстрацію погодинної задачі, включно з числовим інтервалом у секундах.

2026-10-28 після додавання локального limiter виконано `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: **160 тестів без помилок і пропусків**, JAR зібрано. Нові 8 перевірок `AuthRateLimiterTest` охоплюють незалежні ключі/вікна, обидва login-ліміти, округлення Retry-After, звільнення пам'яті, атомарність і конкурентні запити. Сім HTTP-перевірок `AuthRateLimitTest` на PostgreSQL підтверджують `429`/no-store/Retry-After, однакові правила для відомих і невідомих email, ігнорування forwarded headers, відсутність виклику сервісу та змін БД при ліміті, збереження cookie й сім'ї refresh, відновлення після вікна та доступність logout. У наявних тестах реєстрації/логіну/refresh квоти збільшені для ізоляції бізнес-сценаріїв; низькі квоти перевіряються окремим інтеграційним класом.

## Що додати наступним

1. Звірити runtime springdoc з OpenAPI та завершити браузерну інтеграцію auth з frontend.
2. Додати CI з JDK 21, Maven verify і PostgreSQL Testcontainers.

## Джерела

- [Spring Initializr](https://start.spring.io/) — вихідний Maven-каркас і wrapper.
- [Spring Modulith](https://docs.spring.io/spring-modulith/reference/index.html) — BOM і модулі.
- [springdoc](https://springdoc.org/) — OpenAPI/Swagger UI для Spring Boot 4.
- [Spring Boot Maven Plugin](https://docs.spring.io/spring-boot/maven-plugin/packaging.html) — executable packaging.
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) — `application.properties` і змінні середовища.
- [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) — перевірка access-токенів.
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) — cookie repository, deferred token і XOR masking.
