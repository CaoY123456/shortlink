package io.github.caoy123456.shortlink.service;

import io.github.caoy123456.shortlink.config.ShortlinkProperties;
import io.github.caoy123456.shortlink.core.Base62;
import io.github.caoy123456.shortlink.domain.ShortUrl;
import io.github.caoy123456.shortlink.repository.ShortUrlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Optional;

/**
 * The business logic of the service. Two operations: shorten, and resolve.
 */
@Service
public class ShortlinkService {

    private final ShortUrlRepository repository;
    private final ShortlinkProperties properties;

    public ShortlinkService(ShortUrlRepository repository, ShortlinkProperties properties) {
        this.repository = repository;
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
        String shortCode = Base62.encode(id);
        repository.updateShortCode(id, shortCode);

        return properties.baseUrl() + "/" + shortCode;
    }

    /**
     * Resolves a short code to the long URL it points at, and counts the hit.
     *
     * @return the long URL, or empty if the code is unknown
     */
    public Optional<String> resolve(String shortCode) {
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
