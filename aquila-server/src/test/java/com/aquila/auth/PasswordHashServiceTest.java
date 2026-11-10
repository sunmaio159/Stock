package com.aquila.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHashServiceTest {
    private final PasswordHashService passwords = new PasswordHashService();

    @Test
    void hashesWithTheFrozenArgon2idParametersAndVerifiesWithoutPlaintextStorage() {
        String encoded = passwords.hash("correct horse".toCharArray());

        assertThat(encoded).startsWith("argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(encoded).doesNotContain("correct horse");
        assertThat(passwords.matches("correct horse".toCharArray(), encoded)).isTrue();
        assertThat(passwords.matches("wrong horse".toCharArray(), encoded)).isFalse();
    }
}
