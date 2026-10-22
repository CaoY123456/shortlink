package io.github.caoy123456.shortlink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under the {@code shortlink.*} prefix in application.yml.
 *
 * @param baseUrl the public origin used to build returned short URLs,
 *                e.g. {@code http://localhost:8080}. No trailing slash.
 */
@ConfigurationProperties(prefix = "shortlink")
public record ShortlinkProperties(String baseUrl) {
}
