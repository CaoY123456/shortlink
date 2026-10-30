package io.github.caoy123456.shortlink.service;

import io.github.caoy123456.shortlink.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static io.github.caoy123456.shortlink.service.ShortlinkService.COUNT_KEY_PREFIX;

/**
 * Periodically drains the Redis click counters ({@code shortlink:count:*})
 * into MySQL's {@code click_count} column, so Redis does not grow forever
 * and the stats endpoint (which reads MySQL only) eventually catches up.
 *
 * <p>Two invariants {@link #flush()} holds:</p>
 * <ol>
 *   <li>A click counted into Redis before a flush cycle starts ends up in
 *       MySQL eventually - this cycle or a later one. If the MySQL update
 *       fails, the drained delta is put back into Redis.</li>
 *   <li>A click is never added to {@code click_count} twice: GETDEL hands
 *       each counter value to exactly one flusher, and clicks arriving
 *       afterwards start a fresh counter via INCR.</li>
 * </ol>
 */
@Component
public class ClickCountFlushJob {

    private static final Logger log = LoggerFactory.getLogger(ClickCountFlushJob.class);

    private final StringRedisTemplate redis;
    private final ShortUrlRepository repository;

    public ClickCountFlushJob(StringRedisTemplate redis, ShortUrlRepository repository) {
        this.redis = redis;
        this.repository = repository;
    }

    /**
     * Runs on a fixed delay (see {@code shortlink.flush.interval-ms} in
     * application.yml). One run = one full sweep of
     * {@code shortlink:count:*}.
     */
    @Scheduled(fixedDelayString = "${shortlink.flush.interval-ms}")
    public void flush() {
        ScanOptions options = ScanOptions.scanOptions().match(COUNT_KEY_PREFIX + "*").build();
        // SCAN, not KEYS: it does not block Redis. The cursor holds a
        // connection, so it must be closed even if something throws.
        try (Cursor<String> keys = redis.scan(options)) {
            while (keys.hasNext()) {
                flushKey(keys.next());
            }
        } catch (Exception e) {
            log.error("Click count flush aborted", e);
        }
    }

    /** One key's failure is contained here and never stops the rest of the batch. */
    private void flushKey(String key) {
        String shortCode = key.substring(COUNT_KEY_PREFIX.length());

        // Atomic read-and-remove. null means the key vanished between SCAN
        // and now (expired, or another instance flushed it) - nothing to do.
        String raw = redis.opsForValue().getAndDelete(key);
        if (raw == null) {
            return;
        }

        long delta;
        try {
            delta = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // Already deleted and not a number: nothing meaningful to restore.
            log.error("Dropped non-numeric click counter {} = '{}'", key, raw);
            return;
        }
        if (delta <= 0) {
            return;
        }

        try {
            repository.incrementClickCountBy(shortCode, delta);
        } catch (Exception e) {
            log.error("Failed to flush shortCode={} delta={}, restoring to Redis", shortCode, delta, e);
            restore(key, shortCode, delta);
        }
    }

    /** Puts a drained delta back so the next cycle retries it (invariant 1). */
    private void restore(String key, String shortCode, long delta) {
        try {
            redis.opsForValue().increment(key, delta);
        } catch (Exception e) {
            // Both MySQL and Redis are failing; the log line is the only record.
            log.error("LOST clicks: shortCode={} delta={} (repair click_count by hand)", shortCode, delta, e);
        }
    }
}
