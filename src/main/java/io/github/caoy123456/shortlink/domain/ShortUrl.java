package io.github.caoy123456.shortlink.domain;

import java.time.LocalDateTime;

/**
 * One row of the {@code short_url} table.
 *
 * <p>A record, not a class with getters and setters: this is read-only data
 * carried from the database to the caller, so there is nothing to mutate.
 */
public record ShortUrl(
        Long id,
        String shortCode,
        String longUrl,
        long clickCount,
        LocalDateTime createdAt) {
}
