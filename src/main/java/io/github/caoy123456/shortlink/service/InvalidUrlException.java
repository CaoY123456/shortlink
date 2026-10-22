package io.github.caoy123456.shortlink.service;

/**
 * Thrown when a caller submits something that is not a usable http(s) URL.
 *
 * <p>Unchecked on purpose: the caller of {@code shorten} cannot do anything
 * useful about it beyond reporting it, and the web layer turns it into a 400.
 */
public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException(String message) {
        super(message);
    }
}
