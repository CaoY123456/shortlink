# shortlink

A URL shortener built to practice designing, developing, and deploying a
complete project. It takes a long URL, hands back a short one, and redirects
visitors of the short one to the original.

Non-technical goal: improving my English. Code, comments, commit messages,
and docs are written in English on purpose; chat happens in Chinese.

```
POST /api/shorten            { "longUrl": "https://..." }  ->  { "shortUrl": "http://localhost:8080/15ftgG" }
GET  /{shortCode}            ->  302 Location: <the long URL>
GET  /api/stats/{shortCode}  ->  { "shortCode": ..., "longUrl": ..., "clickCount": 3 }
```

## Roadmap (do not reorder)

- [x] **M1** — shorten, redirect, stats against MySQL
- [ ] **M2** — Redis cache on the redirect path, counter in Redis, non-guessable codes
- [ ] **M3** — Dockerfile, whole stack under Compose
- [ ] **M4** — deployed behind Nginx with HTTPS
- [ ] **M5** — polish, published

Current state: M1 is complete. The next planned work is M2 (Redis).
Do not introduce Redis, Docker, or Nginx concerns before their milestone.

## Stack

- Java 17, Spring Boot (Gradle Kotlin DSL, `build.gradle.kts`)
- MySQL 8.4 via Docker Compose, Flyway migrations, no JPA / no Spring Data
- Layers: `web/` (controllers, request/response records) → `service/` →
  `repository/` (JdbcClient) — keep this layering; don't skip layers.

## Running it

Requires JDK 17 and Docker. No Gradle install needed - the wrapper fetches it.

```bash
docker compose up -d          # MySQL on host port 3307
./gradlew bootRun             # app on http://localhost:8080
```

Flyway creates the schema on startup, so there is nothing to set up by hand.
Open `api.http` in IntelliJ and send the requests from there.

```bash
./gradlew test                # unit tests, no database needed
docker compose down           # stop MySQL, keeps the data volume
docker compose down -v        # stop MySQL and wipe the data
```

## How it works

Short codes come from the row's auto-increment id, rendered in base 62
(`0-9A-Za-z`). Base 62 fits more information per character than base 10, so a
10-digit id becomes a 6-character code:

```
1000000000  ->  "15ftgG"
```

The id has to come from the database, because only the database can hand out
ids without two concurrent callers receiving the same one. So `shorten` inserts
first, reads back the generated id, encodes it, and writes the code onto the
row - two statements in one transaction.

`AUTO_INCREMENT` starts at 1000000000 so that codes are 6 characters from the
very first insert rather than growing from 1 character.

Redirects return **302**, not 301. A 301 gets cached hard by browsers, which
would both freeze the mapping and stop the click counter from ever counting
again.

## Layout

```
core/Base62.java              the encoder, and the only real algorithm here
domain/ShortUrl.java          one row of the table
repository/                   all SQL, hand written on JdbcClient
service/                      insert-then-encode, URL validation
web/                          three endpoints + error handling
resources/db/migration/       Flyway schema, version controlled
```

Data access is `JdbcClient` with hand-written SQL rather than MyBatis-Plus or
Spring Data. There is only one table, and the interesting parts - the unique
index on `short_code`, the nullable window between insert and update,
incrementing the counter in SQL instead of in Java - are exactly the parts a
generated CRUD layer would have hidden.

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
2. **Click counter is incremented in SQL**
   (`UPDATE short_url SET click_count = click_count + 1`), not in Java.
3. **`short_code` column is intentionally nullable** — rows are inserted
   first, then the generated code is written back; MySQL allows multiple
   NULLs in the UNIQUE index.
4. **Auto-increment id starts at 1,000,000,000** so every Base62 code is
   ≥ 6 characters from day one.

## Known limitations (accepted for now, M2 will address some)

**Codes are guessable.** Sequential ids produce adjacent codes, so anyone
holding `15ftgG` can walk to `15ftgH` and enumerate every link in the system.
Fine for a learning build, not fine for a real one.

**Every redirect writes to MySQL.** The counter update runs on the hot path, so
throughput is bounded by disk writes.

**Duplicate long URLs get separate codes.** No deduplication.

Fixing the guessability and the hot-path writes is what M2 is for.

## Easy-to-break files

- `src/main/resources/db/migration/` — Flyway. NEVER edit `V1__*.sql`;
  schema changes need a new `V<n>__*.sql` migration.
- MySQL runs on **host port 3307** (not 3306) via `docker-compose.yml`.
- `shortlink.base-url` in `application.yml` builds the returned short
  URL; changing it changes API output.

## Review checklist for every change

- New logic needs tests. Pure unit tests (e.g. `core/Base62Test.java`)
  must not require a database.
- Schema change → new Flyway migration, never edit an existing one.
- Don't touch the four design decisions above without asking first.
- Point out (and fix) accidental `System.out.println`, leftover debug
  code, or comments that restate the code.
