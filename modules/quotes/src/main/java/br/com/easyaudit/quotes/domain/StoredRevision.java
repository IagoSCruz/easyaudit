package br.com.easyaudit.quotes.domain;

import java.time.OffsetDateTime;

public record StoredRevision(int revision, QuoteContent content, String contentHash, OffsetDateTime receivedAt) {
}
