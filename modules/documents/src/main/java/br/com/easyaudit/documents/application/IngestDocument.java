package br.com.easyaudit.documents.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.documents.domain.DocumentType;
import br.com.easyaudit.documents.domain.FiscalHeader;
import br.com.easyaudit.documents.domain.IngestionOutcome;
import br.com.easyaudit.documents.domain.RejectionReason;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.infrastructure.persistence.CancellationRepository;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentRepository;
import br.com.easyaudit.documents.infrastructure.xml.FiscalHeaderParser;
import br.com.easyaudit.documents.infrastructure.xml.InvalidSignatureException;
import br.com.easyaudit.documents.infrastructure.xml.MalformedXmlException;
import br.com.easyaudit.documents.infrastructure.xml.SignatureVerifier;
import br.com.easyaudit.documents.infrastructure.xml.UnsupportedDocumentException;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.Cnpj;
import br.com.easyaudit.shared.TenantId;
import br.com.easyaudit.tenancy.api.TenantDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Porta de entrada de documentos: {@code IngestDocument(tenant, source, bytes)}. O tenant vem do
 * {@link TenantContext}. Cadeia: tamanho → classificação → autorização → assinatura → homologação → posse →
 * idempotência → gravação do original e do registro {@code RECEIVED}, e {@code document.received} pelo outbox,
 * tudo na mesma transação.
 */
@Service
public class IngestDocument {

    /** Limite de um XML. */
    public static final int MAX_XML_BYTES = 1024 * 1024;

    private static final Set<String> DOCUMENT_STATUS = Set.of("100", "150");
    private static final Set<String> EVENT_STATUS = Set.of("135", "136", "155");

    private final TenantDirectory tenants;
    private final DocumentRepository documents;
    private final CancellationRepository cancellations;
    private final RawDocumentStorage storage;
    private final DocumentEventPublisher publisher;
    private final TransactionTemplate tx;
    private final DocumentsProperties properties;

    public IngestDocument(TenantDirectory tenants, DocumentRepository documents, CancellationRepository cancellations,
            RawDocumentStorage storage, DocumentEventPublisher publisher, TransactionTemplate tx,
            DocumentsProperties properties) {
        this.tenants = tenants;
        this.documents = documents;
        this.cancellations = cancellations;
        this.storage = storage;
        this.publisher = publisher;
        this.tx = tx;
        this.properties = properties;
    }

    public IngestionOutcome ingest(Source source, String filename, byte[] bytes) {
        TenantId tenant = TenantContext.require();
        if (bytes.length > MAX_XML_BYTES) {
            return IngestionOutcome.rejected(filename, RejectionReason.FILE_TOO_LARGE,
                    "O XML excede o limite de " + MAX_XML_BYTES + " bytes.");
        }

        FiscalHeader header;
        try {
            header = FiscalHeaderParser.parse(bytes);
        } catch (MalformedXmlException e) {
            return IngestionOutcome.rejected(filename, RejectionReason.MALFORMED_XML, e.getMessage());
        } catch (UnsupportedDocumentException e) {
            return IngestionOutcome.rejected(filename, RejectionReason.UNSUPPORTED_DOCUMENT, e.getMessage());
        }

        Set<String> authorized = header.type().isCancellation() ? EVENT_STATUS : DOCUMENT_STATUS;
        if (header.protocolStatus() == null || !authorized.contains(header.protocolStatus())) {
            return IngestionOutcome.rejected(filename, RejectionReason.NOT_AUTHORIZED,
                    "Sem protocolo de autorização/registro válido (cStat " + header.protocolStatus() + ").");
        }

        try {
            SignatureVerifier.verify(bytes, header.type());
        } catch (InvalidSignatureException e) {
            return IngestionOutcome.rejected(filename, RejectionReason.INVALID_SIGNATURE, e.getMessage());
        } catch (MalformedXmlException e) {
            return IngestionOutcome.rejected(filename, RejectionReason.MALFORMED_XML, e.getMessage());
        }

        if (header.isHomologation() && !properties.acceptHomologation()) {
            return IngestionOutcome.rejected(filename, RejectionReason.HOMOLOGATION_DOCUMENT,
                    "Documento emitido em ambiente de homologação.");
        }

        if (!header.type().isCancellation() && !belongsTo(tenant, header)) {
            return IngestionOutcome.rejected(filename, RejectionReason.NOT_TENANT_DOCUMENT,
                    "O CNPJ do " + (header.type() == DocumentType.CTE ? "tomador" : "emitente/destinatário")
                            + " não pertence ao tenant.");
        }

        String sha256 = sha256(bytes);
        return tx.execute(status -> header.type().isCancellation()
                ? storeCancellation(tenant, filename, header, bytes, sha256)
                : storeDocument(tenant, source, filename, header, bytes, sha256));
    }

    /** CT-e: o tomador é do tenant. NF-e: o emitente ou o destinatário é do tenant. */
    private boolean belongsTo(TenantId tenant, FiscalHeader header) {
        return switch (header.type()) {
            case CTE -> owns(tenant, header.takerCnpj());
            case NFE -> owns(tenant, header.issuerCnpj()) || owns(tenant, header.recipientCnpj());
            default -> false;
        };
    }

    private boolean owns(TenantId tenant, String cnpj) {
        return cnpj != null && Cnpj.isValid(cnpj) && tenants.ownsCnpj(tenant, Cnpj.of(cnpj));
    }

    private IngestionOutcome storeDocument(TenantId tenant, Source source, String filename, FiscalHeader header,
            byte[] bytes, String sha256) {
        DocumentKind kind = header.type() == DocumentType.CTE ? DocumentKind.CTE : DocumentKind.NFE;
        var inserted = documents.insert(kind, header.accessKey(), source, null, sha256);
        if (inserted.isEmpty()) {
            UUID existing = documents.findIdByKey(kind, header.accessKey()).orElseThrow();
            return IngestionOutcome.duplicate(filename, existing);
        }
        UUID id = inserted.get();
        storage.store(id, bytes);
        publisher.received(id, kind, header.accessKey(), false);
        return IngestionOutcome.accepted(filename, id);
    }

    private IngestionOutcome storeCancellation(TenantId tenant, String filename, FiscalHeader header, byte[] bytes,
            String sha256) {
        DocumentKind kind = header.type().isCte() ? DocumentKind.CTE : DocumentKind.NFE;
        var inserted = cancellations.insert(kind, header.accessKey(), header.eventType(), header.eventSequence(),
                sha256, bytes);
        if (inserted.isEmpty()) {
            UUID existing = cancellations.findId(header.accessKey(), header.eventType(), header.eventSequence())
                    .orElseThrow();
            return IngestionOutcome.duplicate(filename, existing);
        }
        publisher.received(inserted.get(), kind, header.accessKey(), true);
        return IngestionOutcome.accepted(filename, inserted.get());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
