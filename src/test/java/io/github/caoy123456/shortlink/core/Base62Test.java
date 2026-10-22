package io.github.caoy123456.shortlink.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62Test {

    @Test
    void encodesKnownValues() {
        assertThat(Base62.encode(0)).isEqualTo("0");
        assertThat(Base62.encode(1)).isEqualTo("1");
        assertThat(Base62.encode(61)).isEqualTo("z");
        // 62 is the first value that needs two characters: 1 * 62 + 0.
        assertThat(Base62.encode(62)).isEqualTo("10");
    }

    @Test
    void firstIdProducesASixCharacterCode() {
        // The table starts at this id precisely so codes are 6 chars from
        // the very first insert. If this fails, check the AUTO_INCREMENT in
        // V1__create_short_url_table.sql.
        assertThat(Base62.encode(1_000_000_000L)).hasSize(6);
    }

    /**
     * The real contract: whatever we encode, we must be able to get back.
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 61, 62, 12345, 1_000_000_000L, Long.MAX_VALUE})
    void encodeThenDecodeReturnsTheOriginal(long value) {
        assertThat(Base62.decode(Base62.encode(value))).isEqualTo(value);
    }

    @Test
    void differentIdsNeverCollide() {
        // Sequential ids must map to distinct codes, otherwise two long URLs
        // would share one short code.
        long start = 1_000_000_000L;
        var codes = new java.util.HashSet<String>();
        for (long id = start; id < start + 10_000; id++) {
            assertThat(codes.add(Base62.encode(id)))
                    .as("code for id %d was already used", id)
                    .isTrue();
        }
    }

    @Test
    void rejectsNegativeValues() {
        assertThatThrownBy(() -> Base62.encode(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCharactersOutsideTheAlphabet() {
        // '-' is not in the alphabet, so this cannot be one of our codes.
        assertThatThrownBy(() -> Base62.decode("ab-de"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
