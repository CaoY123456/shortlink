# shortlink

A URL shortener built to practice designing, developing, and deploying a
complete project. It takes a long URL, hands back a short one, and redirects
visitors of the short one to the original.

Non-technical goal: improving my English. Code, comments, commit messages,
and docs are written in English on purpose; chat happens in Chinese.

```
POST /api/shorten            { "longUrl": "https://..." }  ->  { "shortUrl": "http://localhost:8080/Qx3kZ9" }
GET  /{shortCode}            ->  302 Location: <the long URL>
GET  /api/stats/{shortCode}  ->  { "shortCode": ..., "longUrl": ..., "clickCount": 3 }
```

## Roadmap (do not reorder)

- [x] **M1** — shorten, redirect, stats against MySQL
- [x] **M2** — Redis cache on the redirect path, counter in Redis, non-guessable codes
      (code and tests done; the manual end-to-end check below is still open)
- [ ] **M3** — Dockerfile, whole stack under Compose
- [ ] **M4** — deployed behind Nginx with HTTPS
- [ ] **M5** — polish, published

Do not introduce Docker-for-the-app or Nginx concerns before their milestone.

## Stack

- Java 17, Spring Boot (Gradle Kotlin DSL, `build.gradle.kts`)
- MySQL 8.4 and Redis 7.4 via Docker Compose, Flyway migrations, no JPA / no Spring Data
- Layers: `web/` (controllers, request/response records) → `service/` →
  `repository/` (JdbcClient) — keep this layering; don't skip layers.

## Running it

Requires JDK 17 and Docker. No Gradle install needed - the wrapper fetches it.
On Windows PowerShell use `.\gradlew.bat` instead of `./gradlew`.

```bash
docker compose up -d          # MySQL on host port 3307, Redis on host port 6380
./gradlew bootRun             # app on http://localhost:8080
```

Flyway creates the schema on startup, so there is nothing to set up by hand.
Open `api.http` in IntelliJ and send the requests from there.

Check that both containers are healthy before starting the app:

```bash
docker compose ps             # mysql and redis should show "healthy"
```

Stopping:

```bash
docker compose down           # stop MySQL and Redis, keeps the data volumes
docker compose down -v        # stop them and wipe the data
```

### Tests

```bash
# Pure unit tests - no MySQL or Redis needed (Redis/MySQL are mocked)
./gradlew test --tests '*Base62Test' --tests '*ObfuscatedBase62Test' \
                --tests '*ShortlinkServiceTest' --tests '*ClickCountFlushJobTest'

# Everything - needs `docker compose up -d` first
./gradlew test
```

`ShortUrlRepositoryTest` talks to the real MySQL (each test rolls back its
rows), and `ShortlinkApplicationTests` loads the whole context, so both need
the containers running.

### Manual end-to-end check for M2

This proves the accepted "stats lag by up to one flush cycle" trade-off is
true of the running system, not just the design.

1. `POST /api/shorten`, copy the code from the returned URL.
2. `GET /{code}` several times (each hit does a Redis `INCR`).
3. `GET /api/stats/{code}` right away - `clickCount` is still the old value.
4. Inspect Redis: `docker exec shortlink-redis redis-cli get shortlink:count:<code>`
   shows the pending clicks.
5. Wait for the flush (`shortlink.flush.interval-ms`, 15 s in dev) and call
   stats again - `clickCount` has caught up and the Redis counter key is gone.
6. Redis outage check: `docker compose stop redis`, then `GET /{code}` still
   redirects (degraded MySQL path, logged as a warning). `docker compose start redis`
   to recover.

## How it works

Short codes start from the row's auto-increment id. The id has to come from the
database, because only the database can hand out ids without two concurrent
callers receiving the same one. So `shorten` inserts first, reads back the
generated id, encodes it, and writes the code onto the row - two statements in
one transaction.

`AUTO_INCREMENT` starts at 1000000000 so that codes are 6 characters from the
very first insert rather than growing from 1 character.

**Non-guessable codes (M2).** Plain Base62 of a sequential id gives adjacent
codes (`15ftgG` -> `15ftgH`), so anyone could enumerate every link. The id now
goes through `ObfuscatedBase62` first: a 4-round Feistel network over 2^36
(two 18-bit halves) plus cycle walking to stay inside 62^6, then Base62. It is
a bijection, so distinct ids never collide and the unique index stays safe.
It is obfuscation, not encryption - see the limitations below.

**Redirect read path (M2).** Cache-aside with Redis in front of MySQL:

- hit: serve from `shortlink:url:{code}` (7 day TTL), count with `INCR shortlink:count:{code}`
- miss: load from MySQL, fill the cache, count
- unknown code: store an empty negative entry for 60 s so a burst of requests
  for a bogus code hits MySQL once (cache penetration protection)
- Redis down: any `DataAccessException` degrades to the M1 path (MySQL lookup
  plus a direct `click_count + 1`). Availability beats accuracy.

**Click counting (M2).** The hot path only does a Redis `INCR`.
`ClickCountFlushJob` runs on a fixed delay, walks `shortlink:count:*` with
`SCAN` (never the blocking `KEYS`), drains each key with `GETDEL`, and adds
the delta to `click_count` in MySQL. If the MySQL update fails, the delta is
put back into Redis so the next cycle retries it. Stats read MySQL only, so
they lag by up to one flush cycle.

Redirects return **302**, not 301. A 301 gets cached hard by browsers, which
would both freeze the mapping and stop the click counter from ever counting
again.

## Layout

```
core/Base62.java              the Base62 encoder
core/ObfuscatedBase62.java    Feistel + cycle walking, layered on Base62
domain/ShortUrl.java          one row of the table
repository/                   all SQL, hand written on JdbcClient
service/ShortlinkService      insert-then-encode, URL validation, Redis read path
service/ClickCountFlushJob    scheduled Redis -> MySQL counter flush
web/                          three endpoints + error handling
resources/db/migration/       Flyway schema, version controlled
```

Data access is `JdbcClient` with hand-written SQL rather than MyBatis-Plus or
Spring Data. There is only one table, and the interesting parts - the unique
index on `short_code`, the nullable window between insert and update,
incrementing the counter in SQL instead of in Java - are exactly the parts a
generated CRUD layer would have hidden.

## Collaboration mode

This is a **learning build**: the goal is skill acquisition, not delivery
speed. Who writes a piece of code is decided by one question: *is this what I
am here to learn?*

- **Human writes** the core logic, algorithms, and advanced patterns on first
  encounter (Redis lookup, cache-miss fallback, counters, code obfuscation,
  cache penetration, degradation paths). An imperfect first attempt is
  expected; the AI reviews afterwards.
- **AI writes** infrastructure and boilerplate (Compose services, starter
  dependencies, `application.yml` wiring, repetitive glue, test scaffolding).
- **Flow:** before coding the AI lays out options and trade-offs and the human
  decides; while coding the AI gives hints, not full solutions, unless asked
  ("just give me the answer"); after coding the AI does a proper review (bugs,
  risks, simpler alternatives). Review findings are left as `TODO(review #n)`
  comments for the human to resolve.
- **Mode switch:** in M3 (pure deployment) and M5 (polish/publish) the goal is
  getting it done, so it becomes "AI writes, human verifies".

## Conventions

- Language: English in code, comments, commit messages, and docs.
  Chinese only in conversational replies.
- Data access: hand-written SQL through `JdbcClient`
  (`repository/ShortUrlRepository.java`). Do NOT introduce JPA,
  MyBatis, or Spring Data repositories.
- Comments explain "why", not "what" — teach the reader the trade-off
  behind the decision (see existing code for the style).
- DTOs are Java records (`web/ShortenRequest.java` etc.).
  Configuration is a record bound via `@ConfigurationProperties`
  (`config/ShortlinkProperties.java`).

## Design decisions that must NOT be changed silently

These are documented trade-offs. Changing any of them requires asking
the owner first:

1. **302 redirects, never 301.** 301 gets hard-cached by browsers and
   freezes the mapping, killing the click counter
   (`web/ShortlinkController.java#redirect`).
2. **Clicks are never counted in Java.** The M1 path increments in SQL
   (`click_count = click_count + 1`); the M2 path counts in Redis (`INCR`)
   and the flush job adds the delta in SQL (`click_count = click_count + :delta`).
3. **`short_code` column is intentionally nullable** — rows are inserted
   first, then the generated code is written back; MySQL allows multiple
   NULLs in the UNIQUE index.
4. **Auto-increment id starts at 1,000,000,000** so every Base62 code is
   ≥ 6 characters from day one.
5. **`ObfuscatedBase62.KEYS` are frozen once real codes are issued.** The
   keys are part of the mapping; changing one afterwards makes new codes
   collide with old ones and old codes undecodable.
6. **Flush uses `GETDEL`, never `GET` then `DEL`.** The atomic drain is what
   keeps a click from being counted twice.

## Known limitations (accepted for now)

**Obfuscation is not secrecy.** The Feistel keys are in the source, so anyone
who reads the repo can compute every code. It defeats casual sequential
walking only. A real deployment would load a secret key from configuration
and/or use an audited format-preserving scheme (FF1/FF3).

**Stats lag.** Counts shown by `/api/stats` trail the real number by up to one
flush interval, and in the worst case (MySQL and Redis both failing during a
flush) a batch of clicks is lost; the log line records the short code and delta
so it can be repaired by hand.

**Duplicate long URLs get separate codes.** No deduplication.

## Easy-to-break files

- `src/main/resources/db/migration/` — Flyway. NEVER edit `V1__*.sql`;
  schema changes need a new `V<n>__*.sql` migration.
- MySQL runs on **host port 3307** (not 3306) and Redis on **host port 6380**
  (not 6379) via `docker-compose.yml`.
- `shortlink.base-url` in `application.yml` builds the returned short
  URL; changing it changes API output.
- `shortlink.flush.interval-ms` controls how often counters are flushed;
  keep it short in development, use minutes in a real deployment.

## Review checklist for every change

- New logic needs tests. Pure unit tests (e.g. `core/Base62Test.java`,
  `service/ShortlinkServiceTest.java`) must not require a database or Redis.
- Schema change → new Flyway migration, never edit an existing one.
- Don't touch the design decisions above without asking first.
- Point out (and fix) accidental `System.out.println`, leftover debug
  code, or comments that restate the code.
