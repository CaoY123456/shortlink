package io.github.caoy123456.shortlink.repository;

import io.github.caoy123456.shortlink.domain.ShortUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real MySQL from docker-compose (port 3307), schema created
 * by Flyway. {@code @JdbcTest} wraps each test in a transaction that is rolled
 * back, so no rows are left behind. Start {@code docker compose up -d mysql}
 * before running.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ShortUrlRepository.class)
class ShortUrlRepositoryTest {

    private static final String LONG_URL = "https://example.com/some/long/path";

    @Autowired
    private ShortUrlRepository repository;

    private long id;
    private String code;

    @BeforeEach
    void insertRow() {
        id = repository.insertLongUrl(LONG_URL);
        // Unique per test run so a leftover row can never collide.
        code = "t" + id;
        repository.updateShortCode(id, code);
    }

    private long clickCount() {
        return repository.findByShortCode(code).map(ShortUrl::clickCount).orElseThrow();
    }

    @Test
    void insertedRowStartsWithZeroClicks() {
        assertThat(clickCount()).isZero();
    }

    @Test
    void incrementClickCountByAddsTheDelta() {
        repository.incrementClickCountBy(code, 5);

        assertThat(clickCount()).isEqualTo(5);
    }

    @Test
    void incrementClickCountByAccumulatesAcrossCalls() {
        repository.incrementClickCountBy(code, 5);
        repository.incrementClickCountBy(code, 7);

        assertThat(clickCount()).isEqualTo(12);
    }

    @Test
    void incrementClickCountByWorksAlongsideSingleIncrement() {
        repository.incrementClickCount(code);
        repository.incrementClickCountBy(code, 4);

        assertThat(clickCount()).isEqualTo(5);
    }

    @Test
    void incrementClickCountByIgnoresUnknownCode() {
        repository.incrementClickCountBy("no-such-code", 5);

        // No row matched, so nothing changes and nothing throws.
        assertThat(clickCount()).isZero();
    }

    @Test
    void incrementClickCountByOnlyTouchesTheTargetRow() {
        long otherId = repository.insertLongUrl("https://example.com/other");
        String otherCode = "t" + otherId;
        repository.updateShortCode(otherId, otherCode);

        repository.incrementClickCountBy(code, 3);

        long other = repository.findByShortCode(otherCode).map(ShortUrl::clickCount).orElseThrow();
        assertThat(other).isZero();
        assertThat(clickCount()).isEqualTo(3);
    }
}
