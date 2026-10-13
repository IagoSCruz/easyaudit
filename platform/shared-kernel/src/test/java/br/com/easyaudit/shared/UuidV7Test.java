package br.com.easyaudit.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void hasVersion7AndVariant2() {
        UUID id = UuidV7.next();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheTimestampAndSortsByTime() {
        UUID early = UuidV7.next(1_000_000L);
        UUID late = UuidV7.next(2_000_000L);
        assertThat(early.getMostSignificantBits() >>> 16).isEqualTo(1_000_000L);
        assertThat(early.toString()).isLessThan(late.toString());
    }
}
