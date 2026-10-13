package br.com.easyaudit.audit.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Consulta de casos para outros módulos (a liquidação). Vale para o tenant do contexto corrente. */
public interface AuditCaseQueries {

    /** Dados do caso de que a liquidação precisa; {@code approvedAmount} é nulo enquanto o caso não foi aprovado. */
    record CaseInfo(UUID id, String status, UUID cteDocumentId, String cteAccessKey, String carrierCnpj,
            String carrierName, LocalDate emissionDate, BigDecimal chargedAmount, BigDecimal approvedAmount) {

        /** Raiz do CNPJ da transportadora (8 primeiros caracteres). */
        public String carrierRoot() {
            return carrierCnpj.substring(0, 8);
        }
    }

    /** Os casos que existem entre os ids informados (os desconhecidos ficam de fora). */
    List<CaseInfo> findAll(Collection<UUID> caseIds);
}
