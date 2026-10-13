package br.com.easyaudit.quotes.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StoredQuote(UUID id, Source source, String externalId, String carrierCnpj, String nfeKey,
        String orderReference, LinkStatus linkStatus, LinkMethod linkMethod, UUID linkedNfeDocumentId,
        int currentRevision, OffsetDateTime createdAt) {

    public enum Source { VTEX, INTELIPOST, FRETE_RAPIDO, OTHER }

    public enum LinkStatus { UNLINKED, LINKED, AMBIGUOUS_ORDER_REFERENCE }

    public enum LinkMethod { KEY, ORDER }
}
