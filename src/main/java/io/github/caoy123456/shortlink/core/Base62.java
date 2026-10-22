package io.github.caoy123456.shortlink.core;

/**
 * Converts a number to a short string and back, using a 62 character alphabet.
 *
 * <p>The idea: a database id is a base-10 number, which is a wasteful way to
 * spend characters. Base 10 gives 10 possibilities per character; using digits
 * plus both letter cases gives 62. So the same value needs far fewer
 * characters:
 *
 * <pre>
 *   1_000_000_000 in base 10 -> "1000000000"  (10 chars)
 *   1_000_000_000 in base 62 -> "15FTGf"      (6 chars)
 * </pre>
 *
 * <p>The algorithm is just repeated division, the same one you would use to
 * print a number in binary - only the divisor is 62 instead of 2.
 *
 * <p>Note this mapping is <em>reversible and sequential</em>: id 1000000000
 * and id 1000000001 produce adjacent codes. That makes every link in the
 * system guessable by incrementing a code. We accept that for M1 and fix it
 * later - see the note in README.
 */
public final class Base62 {

    /**
     * Digits first, then upper case, then lower case. The order is arbitrary
     * but must never change: changing it would invalidate every code already
     * handed out.
     */
    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final int BASE = ALPHABET.length();

    private Base62() {
        // Utility class, never instantiated.
    }

    /**
     * Encodes a non-negative number into a Base62 string.
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, got " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }

        // Build the string from the least significant digit up, so we prepend
        // rather than append. StringBuilder.reverse() at the end is the
        // cheaper equivalent.
        StringBuilder sb = new StringBuilder();
        long remaining = value;
        while (remaining > 0) {
            int digit = (int) (remaining % BASE);
            sb.append(ALPHABET.charAt(digit));
            remaining /= BASE;
        }
        return sb.reverse().toString();
    }

    /**
     * Decodes a Base62 string back into the original number.
     *
     * <p>Not used by the service itself (we look codes up in the database
     * instead of decoding them), but having the inverse makes the encoder
     * testable: encode then decode must return what you started with.
     */
    public static long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must not be empty");
        }

        long value = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = ALPHABET.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException(
                        "illegal character '" + code.charAt(i) + "' in code " + code);
            }
            // Shift what we have one place left, then add the new digit -
            // exactly how you read "123" as ((1*10)+2)*10+3.
            value = value * BASE + digit;
        }
        return value;
    }
}
