package br.com.easyaudit.documents.domain;

/**
 * Cabeçalho extraído de um XML fiscal: o suficiente para classificar, autorizar, checar posse e deduplicar sem
 * normalizar o documento inteiro.
 *
 * @param type            tipo do documento
 * @param version         versão do layout ({@code versao} do {@code infCte}/{@code infNFe}/evento)
 * @param environment     {@code tpAmb}: 1 produção, 2 homologação
 * @param accessKey       chave de acesso de 44 dígitos (do documento, ou do documento cancelado nos eventos)
 * @param protocolStatus  {@code cStat} do protocolo de autorização (documentos) ou de registro (eventos); nulo se ausente
 * @param issuerCnpj      CNPJ do emitente (CT-e/NF-e) ou do autor do evento
 * @param takerCnpj       CNPJ do tomador do serviço (CT-e); nulo se o tomador for pessoa física
 * @param recipientCnpj   CNPJ do destinatário (NF-e); nulo se for CPF ou ausente
 * @param eventType       {@code tpEvento} (eventos)
 * @param eventSequence   {@code nSeqEvento} (eventos)
 */
public record FiscalHeader(DocumentType type, String version, int environment, String accessKey,
        String protocolStatus, String issuerCnpj, String takerCnpj, String recipientCnpj, String eventType,
        Integer eventSequence) {

    public static final String CANCELLATION_EVENT = "110111";
    public static final int HOMOLOGATION = 2;

    public boolean isHomologation() {
        return environment == HOMOLOGATION;
    }
}
