---
name: Conventions and Risky Areas
alwaysApply: true
---

# Conventions

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

# Design decisions that must NOT be changed silently

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

# Known limitations (accepted for now, M2 will address some)

- Codes derived from sequential ids are guessable.
- No URL dedup — the same long URL creates a new row each time.
- Every redirect writes to MySQL (hot path).

# Easy-to-break files

- `src/main/resources/db/migration/` — Flyway. NEVER edit `V1__*.sql`;
  schema changes need a new `V<n>__*.sql` migration.
- MySQL runs on **host port 3307** (not 3306) via `docker-compose.yml`.
- `shortlink.base-url` in `application.yml` builds the returned short
  URL; changing it changes API output.

# Review checklist for every change

- New logic needs tests. Pure unit tests (e.g. `core/Base62Test.java`)
  must not require a database.
- Schema change → new Flyway migration, never edit an existing one.
- Don't touch the four design decisions above without asking first.
- Point out (and fix) accidental `System.out.println`, leftover debug
  code, or comments that restate the code.
