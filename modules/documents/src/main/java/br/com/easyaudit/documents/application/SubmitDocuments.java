package br.com.easyaudit.documents.application;

import java.util.ArrayList;
import java.util.List;

import br.com.easyaudit.documents.domain.IngestionOutcome;
import br.com.easyaudit.documents.domain.RejectionReason;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.infrastructure.xml.ZipExpander;
import org.springframework.stereotype.Service;

/**
 * Recebe arquivos de upload ou itens de webhook: um XML vira um resultado; um ZIP é expandido em memória e cada
 * entrada vira um resultado (a falha de uma entrada não afeta as outras).
 */
@Service
public class SubmitDocuments {

    private final IngestDocument ingest;

    public SubmitDocuments(IngestDocument ingest) {
        this.ingest = ingest;
    }

    public List<IngestionOutcome> submit(Source source, String filename, byte[] content) {
        if (!ZipExpander.looksLikeZip(content)) {
            return List.of(ingest.ingest(source, filename, content));
        }
        List<ZipExpander.Entry> entries;
        try {
            entries = ZipExpander.expand(content);
        } catch (ZipExpander.ArchiveRejectedException e) {
            RejectionReason reason = e.getMessage().startsWith("ZIP inválido") || e.getMessage().startsWith("Não foi")
                    ? RejectionReason.MALFORMED_XML : RejectionReason.FILE_TOO_LARGE;
            return List.of(IngestionOutcome.rejected(filename, reason, e.getMessage()));
        }
        List<IngestionOutcome> results = new ArrayList<>();
        for (ZipExpander.Entry entry : entries) {
            String name = filename + "/" + entry.name();
            results.add(entry.tooLarge()
                    ? IngestionOutcome.rejected(name, RejectionReason.FILE_TOO_LARGE,
                            "O XML excede o limite de " + IngestDocument.MAX_XML_BYTES + " bytes.")
                    : ingest.ingest(source, name, entry.content()));
        }
        return results;
    }
}
