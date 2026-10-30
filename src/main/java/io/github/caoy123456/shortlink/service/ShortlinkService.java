package io.github.caoy123456.shortlink.service;

import io.github.caoy123456.shortlink.config.ShortlinkProperties;
import io.github.caoy123456.shortlink.core.Base62;
import io.github.caoy123456.shortlink.core.ObfuscatedBase62;
import io.github.caoy123456.shortlink.domain.ShortUrl;
import io.github.caoy123456.shortlink.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * The business logic of the service. Two operations: shorten, and resolve.
 */
@Service
public class ShortlinkService {

    private static final Logger log = LoggerFactory.getLogger(ShortlinkService.class);

    /** Redis key holding the cached long URL: shortlink:url:{code}. */
    static final String URL_KEY_PREFIX = "shortlink:url:";

    /** Redis key holding unflushed clicks: shortlink:count:{code}. */
    static final String COUNT_KEY_PREFIX = "shortlink:count:";

    /** Sentinel stored for unknown codes - cache penetration protection. */
    static final String NEGATIVE_CACHE_VALUE = "";

    /** TTL of a cached long URL. */
    static final Duration URL_TTL = Duration.ofDays(7);

    /**
     * TTL of a negative cache entry. Short on purpose: a miss is a fact
     * about the *current* database, and short codes never become valid
     * later, so this only needs to outlive a burst of misses for the same
     * unknown code.
     */
    static final Duration NEGATIVE_TTL = Duration.ofSeconds(60);

    private final ShortUrlRepository repository;
    private final StringRedisTemplate redis;
    private final ShortlinkProperties properties;

    public ShortlinkService(ShortUrlRepository repository,
                            StringRedisTemplate redis,
                            ShortlinkProperties properties) {
        this.repository = repository;
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * Stores a long URL and returns the full short URL for it.
     *
     * <p>Two statements, insert then update, so they share a transaction: if
     * the update fails we must not leave a row with a NULL short_code behind.
     *
     * <p>Why not compute the code before inserting? Because the code is
     * derived from the auto-increment id, and only the database can tell us
     * what that id will be. Asking the database for "the next id" separately
     * would be a race condition - two concurrent callers could both be handed
     * the same number.
     */
    @Transactional
    public String shorten(String longUrl) {
        String normalized = normalize(longUrl);

        long id = repository.insertLongUrl(normalized);
        String shortCode = Base62.encode(ObfuscatedBase62.obfuscate(id));
        repository.updateShortCode(id, shortCode);

        return properties.baseUrl() + "/" + shortCode;
    }

    /**
     * Resolves a short code to the long URL it points at, and counts the hit.
     *
     * <p>M2 read path: cache-aside with Redis in front of MySQL.</p>
     * <ol>
     *   <li>Cache hit - serve from Redis, count with INCR (MySQL untouched).</li>
     *   <li>Cache miss - load MySQL, fill the cache, count. Unknown codes
     *       get a {@code ""} negative entry so a burst of misses for the
     *       same bogus code hits the database once, not a thousand times.</li>
     * </ol>
     *
     * <p>Clicks live in {@code shortlink:count:{code}} until the flush job
     * moves them into {@code click_count}; stats may lag by one flush cycle
     * (accepted M2 trade-off). The counter key has no TTL on purpose - it
     * must survive until the flush job GETDELs it.</p>
     *
     * <p>If Redis is unreachable, everything above is skipped and we fall
     * back to the M1 path: MySQL lookup plus a direct click UPDATE.
     * Availability beats accuracy; degraded operation is logged once per
     * failure, not per request.</p>
     *
     * @return the long URL, or empty if the code is unknown
     */
    public Optional<String> resolve(String shortCode) {
        try {
            return resolveViaRedis(shortCode);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, degrading to MySQL for code {}: {}",
                    shortCode, e.getMessage());
            return resolveViaMySql(shortCode);
        }
    }

    private Optional<String> resolveViaRedis(String shortCode) {
        String urlKey = URL_KEY_PREFIX + shortCode;
        String cached = redis.opsForValue().get(urlKey);

        if (cached != null) {
            // Negative cache: we already learned this code is unknown.
            if (cached.equals(NEGATIVE_CACHE_VALUE)) {
                return Optional.empty();
            }
            countClick(shortCode);
            return Optional.of(cached);
        }

        // Miss: fetch from MySQL and fill the cache.
        Optional<ShortUrl> found = repository.findByShortCode(shortCode);
        if (found.isPresent()) {
            redis.opsForValue().set(urlKey, found.get().longUrl(), URL_TTL);
            countClick(shortCode);
            return found.map(ShortUrl::longUrl);
        }

        // Unknown code: remember the miss briefly.
        redis.opsForValue().set(urlKey, NEGATIVE_CACHE_VALUE, NEGATIVE_TTL);
        return Optional.empty();
    }

    /** Redis-fast path click counting; the flush job drains these keys. */
    private void countClick(String shortCode) {
        redis.opsForValue().increment(COUNT_KEY_PREFIX + shortCode);
    }

    /** M1 fallback when Redis is down: correct, just slower and noisier. */
    private Optional<String> resolveViaMySql(String shortCode) {
        Optional<ShortUrl> found = repository.findByShortCode(shortCode);

        // Only count clicks that actually resolved - a 404 is not a visit.
        found.ifPresent(url -> repository.incrementClickCount(shortCode));

        return found.map(ShortUrl::longUrl);
    }

    /**
     * Returns the stored row for a code, for the stats endpoint.
     */
    public Optional<ShortUrl> findByShortCode(String shortCode) {
        return repository.findByShortCode(shortCode);
    }

    /**
     * Rejects anything that is not an absolute http(s) URL.
     *
     * <p>This matters more than it looks. Without the scheme check, a caller
     * could store {@code javascript:alert(1)} and we would hand that back in a
     * redirect - turning the service into a way to launch scripts from our own
     * domain. Restricting to http and https closes that.
     */
    private String normalize(String longUrl) {
        String trimmed = longUrl.trim();

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new InvalidUrlException("not a valid URL: " + trimmed);
        }

        if (!uri.isAbsolute()) {
            throw new InvalidUrlException("URL must include a scheme, e.g. https://example.com");
        }

        String scheme = uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidUrlException("only http and https URLs are supported, got: " + scheme);
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("URL must include a host");
        }

        return trimmed;
    }
}
