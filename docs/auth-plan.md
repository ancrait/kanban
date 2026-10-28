# KP-004 — план реалізації backend-модуля auth

Оновлено: 2026-10-28. Статус: **є persistence, DTO, Spring Security, JWT, CSRF, всі auth endpoint, очищення завершених сесій із retention 7 днів і локальний rate limit; браузерна інтеграція та CI ще в плані**.

Поточний результат: `User`, `RefreshSession`, `RefreshToken`, три JPA-репозиторії, блокування refresh-сесії, `V1__init_auth_tables.sql` та перевірка меж модуля. Maven `verify` пройшов: 24 тести на JDK 22.0.2 з Java target 21, persistence та конкуренцію за блокування перевірено на PostgreSQL 16 через Testcontainers. Фактична конфігурація backend використовує `POSTGRES_*`, як описано в оновленому [backend README](../backend/README.md).

2026-10-25 додано `AuthProperties` (`app.auth`), `AuthConfig`, bean UTC `Clock`, TTL/claims/RSA-шляхи та прапорець Secure cookie. Компіляція пройшла; завантаження й валідація конфігурації в Spring-контексті ще потребують перевірки. 24 тести вище — результат повної перевірки від 2026-10-24.

Додано request/response DTO й MVC-обробку помилок у `infrastructure.web`: звичайний `ApiExceptionHandler`, `ApiErrorResponse`, `ApiValidationErrorResponse` та строгий JSON binding. Власні валідатори замінено готовими анотаціями з повідомленнями в DTO; формат помилок — звичайний JSON у контракті `0.1.0-draft.2`.

2026-10-26 додано BCrypt, RS256 `JwtEncoder`/`JwtDecoder`, `JwtService`, два stateless `SecurityFilterChain`, JSON-помилки filters та `GET /api/v1/auth/csrf` із HttpOnly cookie. Swagger доступний лише у `dev`. Повний `clean verify`: 77 тестів без помилок і пропусків — 19 перевірок помилок/DTO, 33 Security, 23 persistence, запуск повного застосунку на PostgreSQL Testcontainers і межі модулів. Security-тести використовують справжні підписані JWT та токен із CSRF endpoint. Скидання CSRF після register/login/logout, refresh cookie й limiter належать до наступних кроків.

2026-10-26 реалізовано `POST /api/v1/auth/register`: BCrypt, транзакційне створення user/session/token, SHA-256 refresh-хеш, `201 AuthResponse`, Location, HttpOnly refresh cookie та видалення CSRF cookie окремими заголовками. Конфлікт саме `uk_users_email` повертає `409 EMAIL_ALREADY_EXISTS`; інші помилки БД залишаються серверними. Повний `clean verify`: **90 тестів без помилок і пропусків** — додано 12 перевірок реєстрації та перевірку Secure cookie у повному застосунку. Конкурентний тест примусово проводить обидва запити через попередню перевірку email, щоб перевірити саме UNIQUE у PostgreSQL; також перевірено rollback після збою запису refresh-токена. Login/me, ротація, logout, limiter та браузерна інтеграція попереду.

2026-10-26 реалізовано `POST /api/v1/auth/login`: пошук нормалізованого email, BCrypt, однакова `401 INVALID_CREDENTIALS` для невідомого email та неправильного пароля, нова незалежна refresh-сесія й `200 AuthResponse`. Для невідомого email виконується перевірка підставного BCrypt-хешу. Логін і реєстрація використовують спільні методи видачі токенів і cookie; CSRF скидається після успіху. Повний `clean verify` на поточному наборі: **101 тест без помилок і пропусків**, зокрема 12 перевірок `LoginTest` із PostgreSQL Testcontainers, справжніми BCrypt, JWT та CSRF. Me, refresh/logout, limiter і браузерна інтеграція залишаються відкритими.

2026-10-26 додано `GET /api/v1/users/me`: `UserController.me()` бере UUID з перевіреного JWT, `AuthService.getCurrentUser()` читає профіль із БД. Відсутній користувач → `401 ACCESS_TOKEN_INVALID`, `WWW-Authenticate: Bearer`, no-store. `createdAt` нового користувача перед записом приведено до мікросекунд, щоб відповіді register і me збігалися з точністю PostgreSQL. Повний `clean verify`: **106 тестів без помилок і пропусків**; п'ять нових сценаріїв у `SecurityApplicationTest` перевіряють register/login → власний профіль, видаленого/невідомого користувача, refresh-only та пошкоджений JWT. Браузерна інтеграція ще попереду.

2026-10-28 реалізовано `POST /api/v1/auth/refresh`: транзакційна ротація, блокування рядка сесії, повторне читання токена після блокування, `flush()` споживання до нового INSERT, продовження TTL і нові JWT/cookie. Replay-відкликання комітиться перед `401 REFRESH_INVALID`; інші сесії не змінюються. Missing/unknown/expired/revoked/reused refresh видаляє cookie, CSRF залишається чинним. Повний `clean verify`: **122 тести без помилок і пропусків**, зокрема 16 нових перевірок PostgreSQL у `RefreshTest`: sliding TTL, прострочений access, replay простроченого спожитого токена активної сім'ї, ізоляція сесій, CSRF, одночасні запити та rollback реального UNIQUE-конфлікту на коміті. Logout, очищення завершених сесій, limiter та браузерна інтеграція ще попереду.

2026-10-28 додано `AuthService.logout(String refreshToken)`: транзакційний пошук і блокування сесії за refresh-хешем, відкликання сім'ї без видалення історії, ідемпотентність. Старий спожитий і прострочений токен також може відкликати чинну сім'ю. Повний `clean verify`: **129 тестів без помилок і пропусків**; сім нових перевірок у `RefreshTest` охоплюють інші сесії, повторний вихід, старий токен, null/empty/unknown та одночасний refresh/logout на PostgreSQL. Перший запуск був зупинений через недоступний Docker; після запуску Engine повна перевірка пройшла. HTTP endpoint, `204` і очищення cookie ще не реалізовано.

2026-10-28 додано `POST /api/v1/auth/logout`: виклик сервісу, видалення refresh і CSRF cookie після коміту, `204` без тіла з no-store. Missing/unknown/revoked refresh також дає `204` із чинним CSRF. Повний `clean verify`: **138 тестів без помилок і пропусків**; 9 нових HTTP-перевірок у `RefreshTest` охоплюють cookie, відсутні токени, повторний вихід/новий CSRF/наступний login, ізоляцію інших сесій, неправильний CSRF та прострочений Bearer. Limiter, retention/очищення, браузерна інтеграція й CI ще не завершені.

2026-10-28 реалізовано планове очищення завершених refresh-сесій із погодженим retention **7 днів** після першої з подій: expiry або revocation. Щогодини видаляється до 500 сесій; історія видаляється каскадно, активні сім'ї зберігаються повністю, заблоковані рядки пропускаються через `SKIP LOCKED`. Повний `clean verify`: **145 тестів без помилок і пропусків**. Шість нових PostgreSQL-перевірок охоплюють retention, межу часу, порції, каскад і блокування; ще одна перевіряє реальну реєстрацію задачі в Spring та секунди для числового інтервалу.

Додано `AuthRateLimiter`: register — 5/IP за 1 годину; login — 30/IP та 10/нормалізований email за 15 хвилин; refresh — 120/IP за 1 хвилину. Пороги й вікна налаштовуються через `app.auth.rate-limit.*`. Перевірка в контролері після CSRF і DTO, перед `AuthService`, BCrypt і БД. Локальна пам'ять обмежена 10000 записами, прострочені записи очищаються під час перевірки, активні записи не витісняються. `429 RATE_LIMITED` містить no-store і `Retry-After`, не змінює cookie або auth-дані. Logout залишається доступним. Довільні forwarded headers ігноруються (`server.forward-headers-strategy=none`); довірений proxy потрібно налаштувати окремо. Лічильники скидаються при рестарті й не є спільними між інстансами.

Повний `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify` після додавання limiter: **160 тестів без помилок і пропусків**, JAR зібрано. Додано 8 перевірок лічильників і конкурентності та 7 HTTP-перевірок на PostgreSQL: `429`, no-store, Retry-After, відсутність змін сесій/cookie, нормалізація email, однакові правила для відомих і невідомих користувачів, ігнорування підроблених forwarded headers, відновлення після вікна й доступність logout. Квоти в наявних бізнес-тестах збільшено, щоб вони не залежали від накопичених спроб інших сценаріїв цього класу. Браузерна інтеграція, звірка runtime springdoc і CI лишаються відкритими.

Мета — завершити auth для `v0.1-core`: реєстрація → вхід → отримання поточного користувача → оновлення сесії → вихід. Це задача A1 з необхідними передумовами A0. Наявність цього плану не означає завершення задач у [Plan.md](../Plan.md).

## 1. На чому базується план

Пріоритет джерел: погоджений HTTP-контракт → правила [Plan.md](../Plan.md) та `AGENTS.md` → фактичний код і конфігурація → історія змін. Контракт поки має статус draft; рішення для погодження наведені нижче, без зміни поточного API.

| Джерело | Що враховано |
|---|---|
| [Plan.md](../Plan.md), розділи 5, 7, 9–11 | Межі модулів, схема `auth`, BCrypt, JWT, PostgreSQL, ротація refresh, тести та критерії готовності |
| [OpenAPI](api/openapi.yaml), [пояснення API](api/README.md) | Шість endpoint, DTO, cookie, CSRF, TTL, коди помилок і поведінка frontend |
| [backend/pom.xml](../backend/pom.xml), [backend/README.md](../backend/README.md) | Java 21, Spring Boot 4.1.1, Modulith 2.1.1; залежності Security/JWT, JPA, Flyway і Testcontainers уже додані |
| `ffa3267` — `init project` | Є лише `KanbanApplication` і каркас backend; бізнес-коду та тестів немає |
| `3e93b5e`, merge `3c27457` — KP-001 | Додано API-документацію; merge документації не змінює позначений у ній статус draft |
| `1c4655f`, merge `efc9c14` — KP-003 | PostgreSQL уже є в [docker/docker-compose.yml](../docker/docker-compose.yml): `postgres:16-alpine`, volume, healthcheck, порт на localhost |
| `1ce1b96` — `init frontend` | Є Vite proxy `/api/v1` → `localhost:8080` |
| `ce50cc4` — `login/register page` | В окремій гілці `origin/auth/KP-002-visual` є форми й виклики csrf/register/login/logout; цього коміту немає в поточній гілці |

План складено для локальної гілки `auth/KP-004-backend-authentication` на `efc9c14`, за доступною локально історією та remote refs, без оновлення через fetch.

### Початкові розбіжності на момент складання плану

Це початковий знімок, а не поточний статус виконання; реалізовані частини зазначені на початку документа й у підзадачі 03. Конфігурація БД та опис її змінних у backend README вже узгоджені.

- `application.properties` містить лише `spring.application.name`. Описані в backend README параметри БД, `ddl-auto=validate` та вимкнений Open Session in View фактично не налаштовані.
- PostgreSQL у Compose вже доданий, хоча в старих пунктах плану та README він ще фігурує як наступна задача. Повторно створювати Compose не потрібно.
- Змінні Compose називаються `POSTGRES_*`; backend README обіцяє `DB_*`. Потрібно описати їх відповідність. Spring Boot самостійно не читає `docker/.env`.
- Flyway-міграцій, перевірки меж модулів і CI ще немає. Додані Maven-залежності не замінюють їх реалізацію.
- Frontend-гілка вже використовує `withCredentials`, CSRF перед auth POST та access у пам'яті, але переглянуті API/store ще не реалізують refresh і відновлення сесії. Це окрема інтеграційна залежність.

## 2. Обсяг і контракт

Усі шляхи нижче мають префікс `/api/v1`. Джерело DTO і статусів — OpenAPI.

| Endpoint | Доступ і вхідні дані | Успішний результат | Основні помилки |
|---|---|---|---|
| `GET /auth/csrf` | Публічний, `credentials: include` | `200`, `{token, headerName}`, CSRF cookie | Загальна серверна помилка |
| `POST /auth/register` | CSRF; email, password, displayName | `201`, `AuthResponse`, refresh cookie, `Location: /api/v1/users/me` | `400`, `403 CSRF_INVALID`, `409 EMAIL_ALREADY_EXISTS`, `429 RATE_LIMITED` |
| `POST /auth/login` | CSRF; email, password | `200`, `AuthResponse`, refresh cookie | `400`, `401 INVALID_CREDENTIALS`, `403`, `429` |
| `POST /auth/refresh` | CSRF + refresh cookie; без body і вимоги access JWT | `200`, `AuthResponse`, нова refresh cookie | `401 REFRESH_INVALID` з видаленням refresh cookie, `403`, `429` |
| `POST /auth/logout` | CSRF; access JWT не потрібен | `204` без body, відкликання поточної сесії, видалення refresh cookie | `403 CSRF_INVALID` |
| `GET /users/me` | `Authorization: Bearer <accessToken>` | `200`, `User` | `401` з `ACCESS_TOKEN_MISSING`, `ACCESS_TOKEN_INVALID` або `ACCESS_TOKEN_EXPIRED` |

`AuthResponse`: `accessToken`, `tokenType: Bearer`, `expiresIn`, `user`. `User`: `id`, `email`, `displayName`, `createdAt`. Refresh-токен не входить у JSON. Усі відповіді API, включно з помилками, мають `Cache-Control: no-store`.

Межа KP-004 — користувачі, паролі, токени, сесії, мінімальний профіль і технічна основа перевірки Bearer для наступного модуля `board`. OAuth-вхід, підтвердження email, відновлення пароля, редагування профілю, вихід на всіх пристроях, ролі дошки та STOMP залишаються поза цим обсягом.

## 3. Рішення, які фіксуємо до коду endpoint

Це перелік для погодження в межах KP-004, а не твердження, що рішення вже прийняті. Зміни публічної поведінки спочатку вносимо до OpenAPI та API README.

| Питання | Пропозиція для реалізації |
|---|---|
| Строки дії | Access — 900 секунд; refresh-сесія — 604800 секунд від останньої успішної ротації, як у draft |
| Підпис JWT | RS256, стабільна пара ключів із зовнішньої конфігурації; явні `issuer`, `audience`, допустиме відхилення годинників; без генерації нового production-ключа при старті |
| Паролі | BCrypt; cost вибрати після вимірювання на цільовому середовищі й зафіксувати в конфігурації |
| Refresh | Випадковий opaque-секрет із 32 криптографічно випадкових байтів, Base64URL; у БД лише SHA-256 хеш; одна сім'я ротацій на одну refresh-сесію |
| Схема БД | `users` + `refresh_sessions` + технічна історія `refresh_tokens` у схемі `auth`; модель нижче |
| Rate limit | Для одного інстансу локальний limiter з обмеженим розміром і TTL записів; окремі числові ліміти login/register/refresh та правила ключів зафіксувати до інтеграції |
| Середовища | Локальний HTTP має явне `Secure=false`; production — `Secure=true`; production не стартує без потрібних ключів і параметрів |
| CSRF | Токен повертається через JSON, cookie залишається HttpOnly; узгоджуємо request handler з форматом токена у JSON; після register/login/logout попередній CSRF скидається |

JWT перевірятиме наявний Spring Security Resource Server з локальним `JwtDecoder`; випуск — через `JwtEncoder`. Власний фільтр перевірки JWT та окрему JWT-бібліотеку не додаємо. Перевірку підпису й claims налаштовуємо явно, включно з `aud` і форматом UUID у `sub`. Основа механізму — [Spring Security: JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

## 4. Структура модуля

Запропонована структура в `backend/src/main/java/com/kanban/backend/`:

```text
auth/
  package-info.java                 опис модуля
  api/
    package-info.java               @NamedInterface("api")
    CurrentUser.java               отримання UUID автентифікованого користувача
  internal/
    web/                           AuthController, UserController, request/response DTO
    application/                   сценарії auth, локальний limiter, очищення сесій
    domain/                        User, RefreshSession, RefreshToken, правила сесій
    persistence/                   JPA repositories
    security/                      SecurityFilterChain, JWT, BCrypt, cookie, CSRF
    config/                        валідовані auth properties, Clock
infrastructure/
  web/                             advice та прості DTO помилок
```

HTTP DTO не є міжмодульним API. На початку експортуємо лише потрібний `CurrentUser`; пошук профілів інших користувачів додаємо, коли з'явиться споживач. `auth` не залежить від `board`, `activity` або `notification`. UUID — єдине посилання на користувача в майбутніх модулях.

`infrastructure` містить лише технічний код і має явно оголошений доступний інтерфейс, якщо його використовує auth. Не експортуємо auth entities/repositories і не створюємо спільну бізнес-модель. Правила API та внутрішніх пакетів спираються на [Spring Modulith: Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html).

Додати `ApplicationModules.of(KanbanApplication.class).verify()` і явно задати дозволені залежності модулів. Перевірка повинна запускатися через звичайний Maven `verify`.

## 5. Модель даних і ротація

Назви нижче — проєкт міграції для погодження. Усі ID — UUID, часові поля — UTC `timestamptz`. Foreign key дозволені лише всередині схеми `auth`.

| Таблиця | Основні поля та обмеження |
|---|---|
| `auth.users` | `id`, нормалізований `email` з UNIQUE, `password_hash`, `display_name`, `created_at`; обов'язкові поля NOT NULL, узгоджені ліміти довжини |
| `auth.refresh_sessions` | `id` як ID сім'ї ротацій, `user_id` FK → users, `created_at`, `last_rotated_at`, `expires_at`, nullable `revoked_at`; індекси для пошуку сесій користувача й очищення |
| `auth.refresh_tokens` | `id`, `session_id` FK → refresh_sessions, `token_hash` UNIQUE, `issued_at`, `expires_at`, nullable `consumed_at`; індекс `session_id`, partial UNIQUE за `session_id WHERE consumed_at IS NULL` для одного невикористаного токена на сесію |

`RefreshSession` — доменна сесія, а `refresh_tokens` зберігає історію її ротацій для виявлення повторного використання. Окрема технічна таблиця не додає нової публічної функції.

Порядок refresh:

1. Обчислити хеш cookie, знайти запис токена та відповідну сесію. Невідомий токен → `401 REFRESH_INVALID` і очищення cookie.
2. У транзакції заблокувати рядок **сесії**, потім повторно прочитати стан токена. Refresh, logout і відкликання використовують однаковий порядок блокування.
3. Перевірити стан сесії. Для активної сім'ї спочатку перевірити `consumed_at`: повторне використання спожитого токена відкликає всю сім'ю, навіть коли власний строк старого токена вже минув. Потім перевірити строк ще не спожитого токена.
4. Для чинного токена атомарно встановити `consumed_at`, створити наступний хеш і продовжити `expires_at` сесії на 7 днів. Токен і сесія мають узгоджені строки дії.
5. Видавати успішну відповідь і нову cookie лише після успішного збереження. При збої транзакції старий токен залишається придатним.

Відкликання при replay має **закомітитися до повернення `401`**. Не можна втратити його через rollback після викидання runtime exception: транзакційний сценарій повертає результат, а web-рівень формує помилку після завершення транзакції.

Два одночасні refresh одним токеном: перший може завершитися `200`, другий виявляє replay та відкликає сім'ю; виданий першим новий refresh після цього теж непридатний. Це наслідок поточного контракту, тому frontend координує refresh між запитами й вкладками. Інша сесія користувача продовжує працювати.

Історію спожитих токенів зберігаємо, поки сім'я активна, навіть якщо власний `expires_at` старого токена минув. Погоджено 2026-10-28: завершені сесії зберігаємо **7 днів** після expiry або відкликання, залежно від того, що сталося раніше. Очищення запускається щогодини (перший запуск — через годину), видаляє до 500 сесій за транзакцію разом із токенами через FK cascade та пропускає заблоковані рядки (`FOR UPDATE SKIP LOCKED`). Активні сім'ї та їх історія не видаляються, інакше сервер втратить можливість розпізнавати старі токени. Retention, інтервал і порція конфігуруються; черга та блокування можуть подовжувати фактичний строк зберігання.

Правило ротації вже задане OpenAPI; принцип збереження зв'язку між токенами й відкликання активного refresh при replay описано в [RFC 9700, §4.14.2](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2). Це орієнтир захисту токенів, без додавання OAuth-сервера до проєкту.

## 6. Послідовність реалізації

Усі підзадачі належать KP-004; номери нижче локальні й не створюють нових номерів задач у трекері. Тести додаємо разом із відповідною поведінкою.

### 01. Зафіксувати auth-контракт і технічні рішення

- [ ] Погодити розділ 3, auth-частину draft та очікування з frontend-розробником.
- [ ] Додати auth ER-діаграму й короткий ADR про JWT, refresh-сім'ю, блокування, replay і локальний rate limit.
- [ ] Уточнити, що custom register/login/logout мають явно скидати CSRF: не покладатися на автоматичний виклик стандартних authentication handlers.

**Результат:** перед реалізацією зрозумілі DTO, строки дії, модель сесій та всі негативні сценарії.

### 02. Узгодити конфігурацію й перевірити запуск

- [ ] Додати реальні datasource properties з `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; Hibernate `ddl-auto=validate`, Open Session in View вимкнути.
- [ ] Описати зв'язок `POSTGRES_DB/USER/PASSWORD/PORT` із `DB_*`, запуск Compose та передачу параметрів backend із IDE/PowerShell.
- [x] Налаштувати auth properties, BCrypt, rate limit, тестову конфігурацію та перевірити binding/валідацію; TTL, RSA-шляхи, issuer/audience, cookie security і UTC `Clock` реалізовані.
- [ ] Перевірити JDK, Maven Wrapper і базовий `verify`; не змінювати вже зафіксовані версії без підтвердженої проблеми сумісності.
- [ ] Визначити явну поведінку вже підключеного Modulith JDBC registry під час старту; auth не має потребувати ще не реалізованої інфраструктури подій A3.

**Результат:** backend підключається до наявного PostgreSQL за документованими налаштуваннями; документація відповідає файлам.

### 03. Додати модуль, Flyway і persistence

- [x] Створити `auth` та архітектурний тест із розділу 4.
- [ ] Додати named interface разом із першим міжмодульним API.
- [x] Додати `V1__init_auth_tables.sql`; версії міграцій глобальні для backend, SQL явно задає схему `auth`.
- [x] Реалізувати JPA mapping, обмеження й індекси з розділу 5.
- [x] Реалізувати repositories та блокування refresh-сесії.
- [x] Перевірити міграцію порожньої PostgreSQL, повторний запуск і Hibernate validation через Testcontainers; використовувати той самий major PostgreSQL, що в Compose.

Під час ротації перед INSERT нового токена потрібно явно виконати `flush()` зміни `consumed_at` старого. Інакше порядок SQL-операцій Hibernate може порушити partial UNIQUE index, навіть якщо обидві зміни виконуються в одній транзакції. Це перевірено persistence-тестом.

`findByIdForUpdate()` викликається всередині транзакції сервісу; блокування утримується до її завершення. Репозиторій токенів не відфільтровує спожиті чи прострочені записи, оскільки вони потрібні для replay detection. Реалізація повного refresh/logout-сценарію належить до підзадачі 07.

**Результат:** схема відтворюється Flyway, а не Hibernate; повторний запуск не змінює й не втрачає дані.

### 04. Реалізувати DTO-валідацію та єдиний формат помилок

- [ ] Email: trim, lowercase з `Locale.ROOT`, потім перевірка формату й максимум 254 символи; нормалізоване значення зберігається в БД.
- [ ] Display name: trim, 2–50 Unicode code points, не порожній. Новий пароль при реєстрації: 12–72 code points; при вході — обов'язковий непорожній пароль. В обох випадках максимум 72 UTF-8 байти, без trim або нормалізації.
- [ ] Використовувати готові анотації Bean Validation/Hibernate Validator з повідомленнями в DTO; не створювати власні класи валідаторів. Required-поля, некоректний JSON, невідомі поля й неправильні типи повертають `400`.
- [ ] Віддавати `application/json`: `status`, `error`, `code`, `message`, `timestamp`; для `@Valid` request body замість `message` — `errors` як «поле → повідомлення».
- [ ] Ті самі DTO застосувати в controller advice, security handlers і limiter; пароль, cookie та токени не включати в помилки й логи.

**Результат:** `400`, `401`, `403`, `409`, `429` і `500` мають контрактний формат незалежно від місця виникнення.

Реалізовано MVC-частину й security handlers: прості DTO помилок, готові анотації в request DTO, строгий JSON і безпечні повідомлення. Статуси MVC та `Cache-Control: no-store` збережено. `ProblemDetail`, writer і власні валідатори прибрано. Залежність auth від `infrastructure :: web` явно дозволена й перевірена. Підзадача повністю закриється після інтеграції бізнес-endpoint і limiter.

### 05. Налаштувати Security, JWT, cookie та CSRF

- [x] Auth-ланцюжок: публічний `GET /auth/csrf`, чотири auth POST із CSRF; Bearer JWT для них не потрібен і його прострочене значення не блокує refresh/logout.
- [x] API-ланцюжок: `/users/me` та майбутні захищені `/api/v1/**` приймають лише Bearer; не автентифікувати їх refresh cookie. CSRF-виключення застосувати лише до маршрутів без cookie-автентифікації.
- [x] Зробити SecurityContext stateless, вимкнути form login, HTTP Basic, стандартний `/logout` і збереження запиту в HTTP session. Явно визначити fallback-доступ та мінімальний health endpoint; Swagger — за профілем, метрики не відкривати публічно.
- [x] Налаштувати `JwtEncoder`/`JwtDecoder`, дозволений алгоритм, `sub`, `iss`, `aud`, `iat`, `exp`; перевіряти `nbf`, якщо він присутній. Не включати паролі, email або ролі дошки в JWT.
- [x] Додати `kanban_refresh` і `kanban_csrf`: HttpOnly, SameSite=Lax, Path=/api/v1/auth, без Domain; Secure за явною конфігурацією середовища. Для refresh `Max-Age=604800`, для очищення — `Max-Age=0` з тим самим Path.
- [x] `GET /auth/csrf` має матеріалізувати deferred token, установити cookie та повернути рівно `{token, headerName: "X-CSRF-TOKEN"}`. Узгодити masking/request handler так, щоб токен із JSON приймався в заголовку; не читати HttpOnly cookie через JavaScript.
- [x] Після register/login/logout явно скидати попередній CSRF; наступний GET видає придатний токен. Кілька cookie віддавати окремими `Set-Cookie` headers.
- [ ] Використовувати same-origin/Vite proxy. Якщо з'явиться прямий cross-origin доступ, окремо погодити allowlist; довільний origin із credentials не дозволяти.

Особливості deferred token, masking та оновлення CSRF після authentication/logout звіряємо з [Spring Security: CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html). Готовий SPA preset адаптуємо до наших імен cookie/header та HttpOnly-вимоги контракту.

Security-основа реалізована та перевірена. Готові атрибути обох cookie, запис і ротація `kanban_refresh`, його видалення при `401 REFRESH_INVALID` та logout, скидання CSRF після реєстрації, логіну й logout. Refresh CSRF не скидає. Same-origin/Vite proxy потребує фінальної браузерної перевірки з frontend.

**Результат:** реальний токен з `/auth/csrf` проходить auth POST, неправильний дає `403 CSRF_INVALID`, а `/users/me` вимагає JWT.

### 06. Реалізувати register, login і users/me

- [x] Register: нормалізація й валідація → BCrypt → транзакційне створення користувача та початкової refresh-сесії → `201 AuthResponse` і cookie.
- [x] UNIQUE у PostgreSQL захищає email від паралельних реєстрацій. Саме конфлікт цього constraint перетворювати на `409 EMAIL_ALREADY_EXISTS`; інші помилки БД не маскувати під дублікат email.
- [x] Login: пошук нормалізованого email, перевірка BCrypt, нова незалежна сесія. Для невідомого email виконувати перевірку dummy hash, повертати той самий `401 INVALID_CREDENTIALS`, що й для неправильного пароля.
- [x] `/users/me`: отримати UUID із перевіреного principal, прочитати профіль, повернути `User`; відсутній користувач із формально чинним JWT → `401 ACCESS_TOKEN_INVALID`.
- [x] Для register перевірити відповідність `expiresIn` фактичному TTL JWT, `Location`, no-store, відсутність password hash і refresh у JSON.
- [x] Для login перевірити DTO, TTL, no-store, cookie, CSRF, незалежність сесій та rollback.
- [x] Перевірити профіль та no-store для `/users/me`, `WWW-Authenticate` при `401`, відсутність секретів, нових cookie та HTTP session.

**Результат:** можна зареєструватися, повторно увійти й прочитати свій профіль; інший користувач має окрему ідентичність та сесії.

### 07. Реалізувати refresh і logout

- [x] Реалізувати транзакційну ротацію, блокування сесії та replay detection з розділу 5.
- [x] Missing/unknown/expired/revoked/reused refresh → `401 REFRESH_INVALID`, refresh cookie видалена. При невалідному CSRF спершу повертається `403`, операція не виконується.
- [x] Сервіс logout відкликає сім'ю поточної refresh-сесії, зокрема коли наданий токен уже був спожитий; використовує те саме блокування, що refresh.
- [x] Без refresh cookie, з невідомою або вже відкликаною сесією logout також повертає `204`, якщо CSRF чинний. Очищає refresh і CSRF cookie, не видає нового access і не відкликає інші сесії.
- [x] Додати очищення завершених сесій та їх історії за правилами retention (7 днів).

**Результат:** refresh одноразовий, replay відкликає сім'ю, logout ідемпотентний. Уже виданий access JWT після виходу може діяти до кінця TTL — це поточна домовленість API.

### 08. Додати rate limit і перевірити негативні сценарії

- [x] Лімітувати login/register/refresh до дорогих операцій BCrypt і БД; точні пороги та вікна винести в конфігурацію.
- [x] Для login врахувати IP та нормалізований email; однакові правила для відомих і невідомих email. Не створювати безстрокове блокування облікового запису.
- [x] Обмежити пам'ять limiter, очищати прострочені записи. Не довіряти довільному `X-Forwarded-For`: forwarded headers вимкнені до окремого налаштування довіреного proxy.
- [x] Повертати `429 RATE_LIMITED`, `Retry-After` у секундах і JSON DTO помилки. Задокументувати, що локальні лічильники скидаються після рестарту та не підходять для кількох інстансів.
- [x] Перевірити filter responses, no-store, безпечний формат помилок/логів і незмінність auth-даних при відмові через CSRF, валідацію чи rate limit. Replay навпаки має зберегти відкликання сім'ї.

**Результат:** ліміти відтворювано спрацьовують і відпускають запити після вікна; Redis не стає передумовою auth.

### 09. Інтегрувати frontend, контрактні перевірки та CI

- [ ] Узгодити підключення `origin/auth/KP-002-visual` із frontend-розробником; сам факт наявності remote-гілки не означає готову інтеграцію в develop.
- [ ] Перевірити форми register/login, повторний CSRF після auth-змін, Bearer для `/users/me`, обробку `code` і помилок полів.
- [ ] Frontend-задача: додати refresh при reload/401, максимум один повтор, координацію запитів і вкладок; `403 CSRF_INVALID` та мережевий `5xx` обробляти окремо від завершення сесії.
- [ ] Звірити auth-частину runtime springdoc з OpenAPI: маршрути, security requirements, required/nullable, DTO, статуси, headers, cookie та JSON-помилки. Поведінку cookie перевіряти HTTP/браузерними сценаріями, а не лише схемою.
- [ ] Додати backend CI на PR: JDK 21, Maven `verify`, PostgreSQL Testcontainers, архітектурний і контрактні тести. Не пропускати інтеграційні тести через відсутність Docker у CI.
- [ ] Оновити backend README, API README та розбіжності Plan.md після фактичної перевірки; зафіксувати виконані команди й результати.

**Результат:** auth працює через браузер і Vite proxy та має відтворювану автоматичну перевірку.

## 7. Обов'язкова матриця тестів

Java-тести розміщуємо в `backend/src/test/java/`, назви — `*Test.java`, щоб їх запускав звичайний `verify`. Для persistence, транзакцій і конкурентних запитів потрібен PostgreSQL Testcontainers, без підміни на H2.

| Група | Що перевірити |
|---|---|
| Валідація | Trim/case email; displayName після trim; Unicode code points; межі 12/72 символи та 72 UTF-8 байти пароля; пароль із пробілами не змінюється; null, unknown fields і malformed JSON |
| Register | `201`, Location, DTO/cookie; BCrypt hash у БД; дублікат і дві конкурентні реєстрації одного нормалізованого email → один user і один `409` |
| Login | Успіх; невідомий email і неправильний пароль → однаковий `401`; незалежні сесії; rate limit |
| JWT і me | Відсутній, прострочений, пошкоджений JWT; неправильний підпис/алгоритм/issuer/audience; невалідний sub; чинний JWT → правильний профіль; лише refresh cookie → `401` |
| CSRF | Отримання реального токена; без header/cookie, підмінений токен → `403`; register/login/refresh/logout захищені; після register/login/logout новий GET працює; старий CSRF без відповідної cookie не проходить |
| Refresh | Ротація і продовження TTL; відсутній/невідомий/expired/revoked токен; replay, зокрема спожитим токеном із минулим власним TTL, відкликає активну сім'ю; новий токен після replay не працює; інша сесія не зачеплена |
| Конкурентність | Два refresh одного токена; refresh проти logout; replay проти нового refresh; у підсумку немає двох чинних гілок або відновленої відкликаної сім'ї |
| Транзакції | Replay-відкликання зберігається попри `401`; збій запису нового refresh відкочує споживання старого; збій register не лишає часткового user/session |
| Logout | `204` без body; очищення cookie; повторний logout; відсутня/відкликана cookie; старий access ще чинний до expiry, refresh після виходу — ні |
| HTTP і limiter | Точні атрибути cookie у local/production, окремі Set-Cookie; no-store; `WWW-Authenticate` для access `401`; JSON DTO помилок у filters; `429`/Retry-After і відновлення після вікна |
| БД і архітектура | Flyway на чистій БД, повторний старт, constraints/індекси, збереження сесій після рестарту; Modulith verify; збереження історії старих токенів активної сім'ї та очищення завершених |
| Браузер | Register → me → reload/refresh → login → logout; помилки форм; кілька вкладок; HTTP local та HTTPS Secure cookie на тестовому середовищі |

Для часу й TTL використовуємо керований `Clock`, для concurrency — синхронізований старт і окремі транзакції. Не замінюємо перевірку реального CSRF лише тестовим `.with(csrf())`, а перевірку JWT — лише mock principal.

## 8. Критерії готовності KP-004

- [ ] Погоджено auth-контракт і рішення; реалізовано всі шість endpoint без розбіжностей зі специфікацією.
- [ ] Паролі й refresh-секрети не зберігаються відкрито; приватний ключ і локальні секрети не потрапили в Git, відповіді або логи.
- [ ] Ротація, replay, logout і конкурентні сценарії перевірені на PostgreSQL; відкликання не губиться через rollback.
- [ ] Валідація, security errors, cookie, CSRF, rate limit і no-store відповідають контракту.
- [ ] Flyway, архітектурний тест, усі backend-тести й контрактні перевірки проходять у CI через `verify`.
- [ ] Реєстрація, вхід, профіль, відновлення та вихід перевірені разом із frontend у браузері; HTTPS cookie перевірені на тестовому середовищі.
- [ ] Backend запускається за README з наявним Compose; конфігурація документації відповідає файлам; результати перевірок записані.
- [ ] Оновлено лише фактично завершені пункти Plan.md. Інші роботи A0/A1 не вважаються виконаними автоматично після auth.

Рекомендовані межі комітів: `KP-004: configure backend persistence`, `KP-004: add auth schema and module boundaries`, `KP-004: add auth security and csrf`, `KP-004: implement registration and login`, `KP-004: implement refresh rotation and logout`, `KP-004: add auth rate limits and integration checks`. Кожна поведінкова зміна включає відповідні тести; за потреби межі комітів об'єднуємо, щоб не залишати неробочий стан.

## 9. Перша практична дія

Продовжити підзадачу **09**: звірити runtime springdoc з OpenAPI, перевірити auth разом із frontend у браузері та додати CI. Backend-сценарії register/login/me, refresh/logout, очищення сесій і limiter реалізовані. Погодження контракту та ER-діаграми з frontend-розробником лишається окремою передумовою завершення A0.

Під час початкової підготовки цього документа переглянуто Markdown, OpenAPI, build/config-файли, Compose, локальну історію комітів і frontend-код у доступній remote-гілці. Після додавання сутностей, V1 та репозиторіїв виконано Maven `verify`: 24 тести пройшли, включно з Flyway, JPA mapping, SQL constraints, пошуком, транзакційним блокуванням та межами модуля. Повний HTTP auth і браузерна інтеграція залишаються наступними задачами.
