package dev.apiforge.management;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashingTest {
    @Test
    void sha256IsStableAndHex() {
        assertThat(Hashing.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void generatedKeysAreUniqueAndPrefixed() {
        String a = Hashing.newApiKey();
        String b = Hashing.newApiKey();
        assertThat(a).startsWith("af_").hasSizeGreaterThan(40).isNotEqualTo(b);
    }
}
