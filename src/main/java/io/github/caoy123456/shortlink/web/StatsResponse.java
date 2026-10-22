package io.github.caoy123456.shortlink.web;

/**
 * Response body of {@code GET /api/stats/{shortCode}}.
 */
public record StatsResponse(String shortCode, String longUrl, long clickCount) {
}
