package br.com.easyaudit.shared;

import java.security.SecureRandom;
import java.util.UUID;

/** UUID versão 7 (RFC 9562): 48 bits de timestamp em milissegundos seguidos de bits aleatórios, ordenável no tempo. */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        return next(System.currentTimeMillis());
    }

    static UUID next(long epochMillis) {
        long randA = RANDOM.nextLong() & 0x0FFFL;
        long msb = ((epochMillis & 0xFFFFFFFFFFFFL) << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
