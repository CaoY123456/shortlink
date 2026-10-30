package io.github.caoy123456.shortlink.service;

import io.github.caoy123456.shortlink.config.ShortlinkProperties;
import io.github.caoy123456.shortlink.core.Base62;
import io.github.caoy123456.shortlink.core.ObfuscatedBase62;
import io.github.caoy123456.shortlink.domain.ShortUrl;
import io.github.caoy123456.shortlink.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;

import static io.github.caoy123456.shortlink.service.ShortlinkService.COUNT_KEY_PREFIX;
import static io.github.caoy123456.shortlink.service.ShortlinkService.NEGATIVE_CACHE_VALUE;
import static io.github.caoy123456.shortlink.service.ShortlinkService.NEGATIVE_TTL;
import static io.github.caoy123456.shortlink.service.ShortlinkService.URL_KEY_PREFIX;
import static io.github.caoy123456.shortlink.service.ShortlinkService.URL_TTL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShortlinkService}: the M2 Redis read path (cache
 * hit, miss + fill, negative cache, outage fallback) and shorten().
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShortlinkServiceTest {

    private static final String CODE = "abc123";
    private static final String LONG_URL = "https://example.com/some/long/path";
    private static final String URL_KEY = URL_KEY_PREFIX + CODE;
    private static final String COUNT_KEY = COUNT_KEY_PREFIX + CODE;

    @Mock
    private ShortUrlRepository repository;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    private ShortlinkService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        service = new ShortlinkService(repository, redis, new ShortlinkProperties("http://localhost:8080"));
    }

    private static ShortUrl row() {
        return new ShortUrl(1_000_000_000L, CODE, LONG_URL, 0, LocalDateTime.now());
    }

    // ---- resolve: cache hit -------------------------------------------------

    @Test
    void cacheHitServesFromRedisAndCountsInRedis() {
        when(values.get(URL_KEY)).thenReturn(LONG_URL);

        Optional<String> result = service.resolve(CODE);

        assertThat(result).contains(LONG_URL);
        verify(values).increment(COUNT_KEY);
        // MySQL is untouched on a hit - that is the whole point of the cache.
        verifyNoInteractions(repository);
    }

    // ---- resolve: miss + fill ----------------------------------------------

    @Test
    void cacheMissLoadsFromMySqlFillsCacheAndCounts() {
        when(values.get(URL_KEY)).thenReturn(null);
        when(repository.findByShortCode(CODE)).thenReturn(Optional.of(row()));

        Optional<String> result = service.resolve(CODE);

        assertThat(result).contains(LONG_URL);
        verify(values).set(URL_KEY, LONG_URL, URL_TTL);
        verify(values).increment(COUNT_KEY);
        // The click is counted in Redis, not written to MySQL on the hot path.
        verify(repository, never()).incrementClickCount(anyString());
    }

    // ---- resolve: negative cache -------------------------------------------

    @Test
    void negativeCacheHitReturnsEmptyWithoutTouchingMySql() {
        when(values.get(URL_KEY)).thenReturn(NEGATIVE_CACHE_VALUE);

        Optional<String> result = service.resolve(CODE);

        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
        // A 404 is not a visit.
        verify(values, never()).increment(anyString());
    }

    @Test
    void unknownCodeFirstTimeWritesNegativeEntry() {
        when(values.get(URL_KEY)).thenReturn(null);
        when(repository.findByShortCode(CODE)).thenReturn(Optional.empty());

        Optional<String> result = service.resolve(CODE);

        assertThat(result).isEmpty();
        verify(values).set(URL_KEY, NEGATIVE_CACHE_VALUE, NEGATIVE_TTL);
        verify(values, never()).increment(anyString());
    }

    // ---- resolve: Redis outage ---------------------------------------------

    @Test
    void redisReadFailureDegradesToMySqlAndCountsInMySql() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        when(repository.findByShortCode(CODE)).thenReturn(Optional.of(row()));

        Optional<String> result = service.resolve(CODE);

        assertThat(result).contains(LONG_URL);
        verify(repository).incrementClickCount(CODE);
    }

    @Test
    void redisFailureOnFillStillServesTheRequest() {
        // Redis answers the read (miss) but dies on the write: still degrade.
        when(values.get(URL_KEY)).thenReturn(null);
        when(repository.findByShortCode(CODE)).thenReturn(Optional.of(row()));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("redis down"))
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));

        Optional<String> result = service.resolve(CODE);

        assertThat(result).contains(LONG_URL);
        verify(repository).incrementClickCount(CODE);
    }

    @Test
    void degradedPathDoesNotCountUnknownCodes() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        when(repository.findByShortCode(CODE)).thenReturn(Optional.empty());

        Optional<String> result = service.resolve(CODE);

        assertThat(result).isEmpty();
        verify(repository, never()).incrementClickCount(anyString());
    }

    // ---- shorten ------------------------------------------------------------

    @Test
    void shortenStoresObfuscatedBase62CodeAndReturnsFullUrl() {
        long id = 1_000_000_007L;
        when(repository.insertLongUrl(LONG_URL)).thenReturn(id);
        String expectedCode = Base62.encode(ObfuscatedBase62.obfuscate(id));

        String shortUrl = service.shorten(LONG_URL);

        verify(repository).updateShortCode(id, expectedCode);
        assertThat(shortUrl).isEqualTo("http://localhost:8080/" + expectedCode);
    }

    @Test
    void shortenTrimsSurroundingWhitespace() {
        when(repository.insertLongUrl(LONG_URL)).thenReturn(1_000_000_000L);

        service.shorten("  " + LONG_URL + "  ");

        verify(repository).insertLongUrl(LONG_URL);
    }

    @Test
    void shortenRejectsNonHttpSchemes() {
        assertThatThrownBy(() -> service.shorten("javascript:alert(1)"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> service.shorten("ftp://example.com/file"))
                .isInstanceOf(InvalidUrlException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void shortenRejectsRelativeUrlsAndMissingHost() {
        assertThatThrownBy(() -> service.shorten("example.com/no-scheme"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> service.shorten("https://"))
                .isInstanceOf(InvalidUrlException.class);

        verifyNoInteractions(repository);
    }

    // ---- stats --------------------------------------------------------------

    @Test
    void findByShortCodeReadsMySqlOnly() {
        when(repository.findByShortCode(CODE)).thenReturn(Optional.of(row()));

        assertThat(service.findByShortCode(CODE)).isPresent();

        // Stats never consult Redis - hence the accepted one-flush-cycle lag.
        verifyNoInteractions(redis);
        verify(repository).findByShortCode(CODE);
        verifyNoMoreInteractions(repository);
    }
}
