package io.github.caoy123456.shortlink.web;

import io.github.caoy123456.shortlink.domain.ShortUrl;
import io.github.caoy123456.shortlink.service.ShortlinkService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

/**
 * The three HTTP endpoints of the service.
 */
@RestController
public class ShortlinkController {

    private final ShortlinkService service;

    public ShortlinkController(ShortlinkService service) {
        this.service = service;
    }

    /**
     * Turns a long URL into a short one.
     */
    @PostMapping("/api/shorten")
    public ShortenResponse shorten(@Valid @RequestBody ShortenRequest request) {
        String shortUrl = service.shorten(request.longUrl());
        return new ShortenResponse(shortUrl);
    }

    /**
     * The redirect. This is the endpoint that carries all the traffic.
     *
     * <p>Returns 302 (Found, a temporary redirect) rather than 301
     * (Moved Permanently) on purpose: browsers cache a 301 aggressively and
     * stop asking us again, which would both freeze the mapping and make the
     * click counter stop counting. A 302 keeps every visit coming through us.
     *
     * <p>Mapped on {@code /{shortCode}} at the root, so the URL stays as short
     * as possible - that is the whole product.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        Optional<String> longUrl = service.resolve(shortCode);

        return longUrl
                .map(url -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(url))
                        .<Void>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * How many times a code has been visited.
     */
    @GetMapping("/api/stats/{shortCode}")
    public ResponseEntity<StatsResponse> stats(@PathVariable String shortCode) {
        Optional<ShortUrl> found = service.findByShortCode(shortCode);

        return found
                .map(url -> ResponseEntity.ok(new StatsResponse(
                        url.shortCode(), url.longUrl(), url.clickCount())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
