package io.github.caoy123456.shortlink.core;

/**
 * Reversible id obfuscation, layered on top of {@link Base62}.
 *
 * <p>M1 encodes the auto-increment id directly, so adjacent ids yield adjacent
 * codes ({@code 15ftgG -> 15ftgH}) and anyone can walk the whole keyspace.
 * This class interposes a <b>bijective mixer</b> between id and Base62: the
 * output looks random, but distinct ids still map to distinct values, so the
 * {@code short_code} unique index never collides and the MySQL insert flow
 * stays untouched.</p>
 *
 * <p>Construction (see the design discussion from 2026-10-22):</p>
 * <ol>
 *   <li><b>Feistel network over 2^36</b>. 62^6 = 56,800,235,584 is not a
 *       power of two, so we work in the next power of two, 2^36 =
 *       68,719,476,736, and split each value into two 18-bit halves.</li>
 *   <li><b>Cycle walking</b>. A Feistel output may land at or above 62^6.
 *       In that case feed the output back through the network until it lands
 *       inside the valid domain. Restricting a bijection to a subset this way
 *       is still a bijection.</li>
 *   <li><b>Base62 as the final step</b>, unchanged from M1 - the 6-character
 *       codes and the alphabet are identical, only the number being encoded
 *       is now obfuscated.</li>
 * </ol>
 *
 * <p>This is obfuscation, not encryption: a 4-round 36-bit toy network stops
 * casual enumeration, which is what the README's "non-guessable codes" asks
 * for. Production would use an audited FPE scheme (FF1/FF3) instead.</p>
 */
public final class ObfuscatedBase62 {

    /** 62^6 = 56,800,235,584. Every valid short code encodes a value below it. */
    static final long DOMAIN = 56_800_235_584L;

    /** The Feistel works on 2^36; values below this are valid network inputs. */
    static final long NETWORK_SIZE = 1L << 36;

    /** Each half of the network is 18 bits. */
    static final int HALF_BITS = 18;

    /** Mask with the low {@value #HALF_BITS} bits set: 0x3FFFF. */
    static final long HALF_MASK = (1L << HALF_BITS) - 1;

    /**
     * Round keys, one per round: the first four words of the SHA-512
     * initial state, which are the fractional parts of the square roots
     * of 2, 3, 5 and 7 - a "nothing up my sleeve" set with published
     * digits, so nobody can suspect the keys were chosen to plant a
     * backdoor into the mapping.
     *
     * <p><b>Frozen forever once real codes are issued.</b> The values are
     * part of the mapping: change one afterwards and every new code risks
     * colliding with an old one, while old codes become undecodable.</p>
     */
    // TODO(review #1, design): these keys are public (in the repo, and the algorithm
    //  is documented right above), so anyone can run obfuscate() themselves and
    //  enumerate every code - "non-guessable" only holds against people who have not
    //  read the source. Decide: (a) load a secret key from config/env (still frozen
    //  once codes are issued), or (b) keep as is and state in the README that this
    //  only defeats casual sequential walking. "Nothing up my sleeve" protects
    //  against a backdoor, not against enumeration.
    static final long[] KEYS = {
            0x6A09E667F3BCC908L, // sqrt(2)
            0xBB67AE8584CAA73BL, // sqrt(3)
            0x3C6EF372FE94F82BL, // sqrt(5)
            0xA54FF53A5F1D36F1L, // sqrt(7)
    };

    private ObfuscatedBase62() {
    }

    /**
     * Maps an id into the domain [0, 62^6) in a way that looks random.
     *
     * <p>Contract: for any two distinct ids below 62^6 the outputs are
     * distinct (bijection), which is what keeps the unique index safe.</p>
     *
     * @param id auto-increment id from MySQL, always &lt; 62^6 in practice
     * @return an obfuscated value in [0, 62^6)
     * @throws IllegalArgumentException if id is outside [0, 62^6)
     */
    public static long obfuscate(long id) {
        // Fail fast: the cycle-walk contract only holds for inputs inside
        // the domain, and an id this large would mean the table is fuller
        // than this design ever anticipated.
        if (id < 0 || id >= DOMAIN) {
            throw new IllegalArgumentException(
                    "id must be in [0, " + DOMAIN + ") got " + id);
        }

        // Cycle walking: run the network once; if the output landed in the
        // strip [62^6, 2^36) that Base62 cannot render as 6 characters, run
        // the network again on its own output. Why forward only, never
        // backwards: the orbit of id under feistel() is a cycle that
        // contains id itself, and id < DOMAIN - so the walk always
        // re-enters the domain, worst case after one full lap.
        long v = feistel(id);
        while (v >= DOMAIN) {
            v = feistel(v);
        }
        return v;
    }

    /**
     * Inverse of {@link #obfuscate}: maps a value in [0, 62^6) back to the
     * original id. Only used by tests to prove the round trip; production
     * lookups go through the database by string, never id.
     *
     * @param value obfuscated value in [0, 62^6)
     * @return the id that produced it
     */
    public static long deobfuscate(long value) {
        // Mirror of the forward walk: undo one network pass at a time, but
        // only *stop* when the previous element of the orbit is itself
        // inside the domain. The forward walk stopped at the first domain
        // element it met; this walk approaches the same element from the
        // other side of the cycle, so it stops at the same place.
        long v = value;
        while (true) {
            long prev = feistelInverse(v);
            if (prev < DOMAIN) {
                return prev;
            }
            v = prev;
        }
    }

    /**
     * One forward pass through the 4-round Feistel network.
     *
     * <p>The structure (any {@code f}, guaranteed invertible):</p>
     * <pre>
     * for each round i:
     *     newL = R                  // right half passes through unchanged
     *     newR = L XOR f(R, KEYS[i])// left half locked with f of the right
     * </pre>
     *
     * @param x a value in [0, 2^36)
     * @return the permuted value, also in [0, 2^36)
     */
    static long feistel(long x) {
        // Split the 36-bit value into two 18-bit halves: L holds the top
        // 18 bits, R the bottom 18. After that, no arithmetic ever touches
        // them as one number again until the halves are glued back.
        long L = x >>> HALF_BITS;
        long R = x & HALF_MASK;

        for (int i = 0; i < KEYS.length; i++) {
            // The swap: the right half crosses over untouched (newL = R),
            // which is what makes every round invertible without ever
            // inverting f - one half is always recoverable as-is.
            long newL = R;

            // XOR: the operator that is its own inverse. Applying f to the
            // half we keep intact locks the other half away, and XORing f
            // in again with the same operand on the way back cancels it.
            long newR = L ^ f(R, KEYS[i]);

            L = newL;
            R = newR;
        }

        // Glue the halves back into one 36-bit value.
        return (L << HALF_BITS) | R;
    }

    /**
     * One backward pass: undoes {@link #feistel} by replaying rounds in
     * reverse with the same keys, using the identity
     * {@code oldL = newR XOR f(newL)} / {@code oldR = newL}.
     */
    static long feistelInverse(long x) {
        // Un-glue the halves, exactly as feistel() did.
        long L = x >>> HALF_BITS;
        long R = x & HALF_MASK;

        // Replay the rounds backwards. Per round we have (names relative
        // to feistel's loop body): newL = R, newR = L ^ f(R). Reading that
        // backwards, the values we hold ARE R and newR:
        //   R (old)   = newL              -> directly available
        //   L (old)   = newR ^ f(R, key)  -> XOR cancels the same f call
        // Note f is recomputed, never inverted - that is the whole trick.
        for (int i = KEYS.length - 1; i >= 0; i--) {
            long oldR = L;              // was newL, which was R
            long oldL = R ^ f(oldR, KEYS[i]);

            L = oldL;
            R = oldR;
        }

        return (L << HALF_BITS) | R;
    }

    /**
     * The round function. May be anything at all - invertibility of the
     * network does not depend on it. Quality of the "random look" does.
     *
     * <p>Goal: avalanche - flipping one input bit should flip about half of
     * the output bits on average.</p>
     *
     * @param r   right half, in [0, 2^18)
     * @param key this round's key
     * @return a value in [0, 2^18) - remember to mask back down to 18 bits
     */
    // TODO(review #2, quality): the code is correct (forward/inverse/cycle-walk all
    //  check out), but f()'s "random look" is only claimed, not measured. Write a
    //  small avalanche check (flip each of the 18 input bits over many samples,
    //  count flipped output bits, expect ~9 of 18) and a check that consecutive ids
    //  0..N do not produce consecutive or clustered codes. If the average is far
    //  from 9, add a round or another xorshift-multiply step.
    static long f(long r, long key) {
        // MurmurHash3-style finalizer, truncated to 18 bits. Multiply-xorshift
        // chains are cheap, fully invertible in isolation, and stir every
        // input bit across the whole 64-bit word - more rounds of a cheap
        // mixer beat one round of a clever one.
        long h = r * 0x9E3779B97F4A7C15L + key;   // golden-ratio increment
        h ^= h >>> 17;
        h *= 0xC2B2AE3D27D4EB4FL;                  // murmur3 finalizer const
        h ^= h >>> 13;
        return h & HALF_MASK;                      // back into [0, 2^18)
    }
}
