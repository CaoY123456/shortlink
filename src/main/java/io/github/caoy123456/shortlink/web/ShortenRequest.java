package io.github.caoy123456.shortlink.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /api/shorten}.
 *
 * <p>The annotations are enforced by {@code @Valid} on the controller method,
 * so a blank or oversized URL is rejected before any of our code runs.
 * 2048 matches the column width in the database.
 */
public record ShortenRequest(
        @NotBlank(message = "longUrl must not be blank")
        @Size(max = 2048, message = "longUrl must be at most 2048 characters")
        String longUrl) {
}
