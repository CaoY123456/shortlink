package io.github.caoy123456.shortlink.service;

import io.github.caoy123456.shortlink.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static io.github.caoy123456.shortlink.service.ShortlinkService.COUNT_KEY_PREFIX;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClickCountFlushJob}: Redis and MySQL are mocks, so
 * these pin down the flush logic (invariants 1 and 2 in the class javadoc)
 * without any infrastructure running.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClickCountFlushJobTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;
    @Mock
    private ShortUrlRepository repository;
    @Mock
    private Cursor<String> cursor;

    private ClickCountFlushJob job;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.scan(org.mockito.ArgumentMatchers.any(ScanOptions.class))).thenReturn(cursor);
        job = new ClickCountFlushJob(redis, repository);
    }

    /** Makes the cursor hand out exactly these keys, then report exhausted. */
    private void scanReturns(String... keys) {
        Boolean[] hasNext = new Boolean[keys.length + 1];
        for (int i = 0; i < keys.length; i++) {
            hasNext[i] = true;
        }
        hasNext[keys.length] = false;

        when(cursor.hasNext()).thenReturn(hasNext[0], java.util.Arrays.copyOfRange(hasNext, 1, hasNext.length));
        if (keys.length > 0) {
            when(cursor.next()).thenReturn(keys[0],
                    java.util.Arrays.copyOfRange(keys, 1, keys.length));
        }
    }

    private static String key(String shortCode) {
        return COUNT_KEY_PREFIX + shortCode;
    }

    @Test
    void flushesDeltaToMySqlUnderTheBareShortCode() {
        scanReturns(key("abc123"));
        when(values.getAndDelete(key("abc123"))).thenReturn("5");

        job.flush();

        // Bare code, not "shortlink:count:abc123" - the repo matches on short_code.
        verify(repository).incrementClickCountBy("abc123", 5L);
    }

    @Test
    void drainsWithGetAndDeleteAndNeverReadsTheKeyAgain() {
        scanReturns(key("abc123"));
        when(values.getAndDelete(key("abc123"))).thenReturn("5");

        job.flush();

        // The atomic GETDEL is the only read; a plain get() would let clicks be
        // counted twice (invariant 2).
        verify(values).getAndDelete(key("abc123"));
        verify(values, never()).get(anyString());
    }

    @Test
    void flushesEveryKeyInTheSweep() {
        scanReturns(key("aaa"), key("bbb"));
        when(values.getAndDelete(key("aaa"))).thenReturn("2");
        when(values.getAndDelete(key("bbb"))).thenReturn("7");

        job.flush();

        verify(repository).incrementClickCountBy("aaa", 2L);
        verify(repository).incrementClickCountBy("bbb", 7L);
    }

    @Test
    void skipsKeyThatVanishedBetweenScanAndGetDel() {
        scanReturns(key("abc123"));
        when(values.getAndDelete(key("abc123"))).thenReturn(null);

        job.flush();

        verifyNoInteractions(repository);
    }

    @Test
    void skipsZeroAndNegativeCounters() {
        scanReturns(key("zero"), key("neg"));
        when(values.getAndDelete(key("zero"))).thenReturn("0");
        when(values.getAndDelete(key("neg"))).thenReturn("-3");

        job.flush();

        // The repository contract says delta must be > 0.
        verifyNoInteractions(repository);
    }

    @Test
    void dropsNonNumericValueWithoutRestoringIt() {
        scanReturns(key("junk"));
        when(values.getAndDelete(key("junk"))).thenReturn("not-a-number");

        job.flush();

        verifyNoInteractions(repository);
        verify(values, never()).increment(anyString(), anyLong());
    }

    @Test
    void restoresDeltaToRedisWhenMySqlUpdateFails() {
        scanReturns(key("abc123"));
        when(values.getAndDelete(key("abc123"))).thenReturn("5");
        doThrow(new RuntimeException("mysql down"))
                .when(repository).incrementClickCountBy("abc123", 5L);

        job.flush();

        // Invariant 1: the drained clicks go back so the next cycle retries.
        verify(values).increment(key("abc123"), 5L);
    }

    @Test
    void doesNotRestoreWhenMySqlUpdateSucceeds() {
        scanReturns(key("abc123"));
        when(values.getAndDelete(key("abc123"))).thenReturn("5");

        job.flush();

        verify(values, never()).increment(anyString(), anyLong());
    }

    @Test
    void oneFailingKeyDoesNotStopTheRestOfTheBatch() {
        scanReturns(key("bad"), key("good"));
        when(values.getAndDelete(key("bad"))).thenReturn("1");
        when(values.getAndDelete(key("good"))).thenReturn("9");
        doThrow(new RuntimeException("boom"))
                .when(repository).incrementClickCountBy("bad", 1L);

        job.flush();

        verify(repository).incrementClickCountBy("good", 9L);
    }

    @Test
    void doesNotThrowWhenRestoreAlsoFails() {
        scanReturns(key("abc123"));
        when(values.getAndDelete(key("abc123"))).thenReturn("5");
        doThrow(new RuntimeException("mysql down"))
                .when(repository).incrementClickCountBy("abc123", 5L);
        when(values.increment(key("abc123"), 5L))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // A scheduled job that throws is logged by Spring and the next run
        // still fires, but swallowing here keeps the log line ours.
        assertThatCode(() -> job.flush()).doesNotThrowAnyException();
    }

    @Test
    void closesTheCursorEvenWhenARedisCallFails() {
        scanReturns(key("abc123"));
        when(values.getAndDelete(key("abc123"))).thenThrow(new QueryTimeoutException("timeout"));

        assertThatCode(() -> job.flush()).doesNotThrowAnyException();

        verify(cursor).close();
    }

    @Test
    void closesTheCursorAfterANormalSweep() {
        scanReturns(key("abc123"));
        when(values.getAndDelete(key("abc123"))).thenReturn("1");

        job.flush();

        verify(cursor).close();
    }
}
