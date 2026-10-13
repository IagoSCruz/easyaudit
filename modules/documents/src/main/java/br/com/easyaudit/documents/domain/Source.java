package br.com.easyaudit.documents.domain;

/** Origem do documento. {@code SEFAZ_DFE} é reservado para a change do worker de Distribuição DF-e. */
public enum Source {
    UPLOAD, WEBHOOK, SEFAZ_DFE
}
