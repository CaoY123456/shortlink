# shortlink

A URL shortener. Takes a long URL, hands back a short one, and redirects
visitors of the short one to the original.

```
POST /api/shorten            { "longUrl": "https://..." }  ->  { "shortUrl": "http://localhost:8080/15ftgG" }
GET  /{shortCode}            ->  302 Location: <the long URL>
GET  /api/stats/{shortCode}  ->  { "shortCode": ..., "longUrl": ..., "clickCount": 3 }
```

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

## Known limitations

**Codes are guessable.** Sequential ids produce adjacent codes, so anyone
holding `15ftgG` can walk to `15ftgH` and enumerate every link in the system.
Fine for a learning build, not fine for a real one.

**Every redirect writes to MySQL.** The counter update runs on the hot path, so
throughput is bounded by disk writes.

**Duplicate long URLs get separate codes.** No deduplication.

Fixing the first two is what M2 is for.

## Roadmap

- [x] **M1** - shorten, redirect, stats against MySQL
- [ ] **M2** - Redis cache on the redirect path, counter in Redis, non-guessable codes
- [ ] **M3** - Dockerfile, whole stack under Compose
- [ ] **M4** - deployed behind Nginx with HTTPS
- [ ] **M5** - polish, published
