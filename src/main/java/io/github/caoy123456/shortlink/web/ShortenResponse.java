package io.github.caoy123456.shortlink.web;

/**
 * Response body of {@code POST /api/shorten}.
 */
public record ShortenResponse(String shortUrl) {
}
