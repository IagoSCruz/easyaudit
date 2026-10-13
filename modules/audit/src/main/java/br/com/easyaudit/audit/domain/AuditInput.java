package br.com.easyaudit.audit.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Snapshot imutável de tudo o que a auditoria de um CT-e precisa: o CT-e, as NF-es vinculadas e as pendentes, a
 * cotação aplicável, o resultado do cálculo de frete, a política efetiva e os CT-es anteriores que referenciam as
 * mesmas NF-es. O motor é uma função pura deste snapshot.
 */
public record AuditInput(Cte cte, List<Nfe> nfes, List<String> pendingNfeKeys, Quote quote, Freight freight,
        AuditPolicy policy, List<EarlierCte> earlierCtes) {

    public enum CteType { NORMAL, COMPLEMENT, SUBSTITUTE }

    public enum FreightMode { CIF, FOB, THIRD_PARTY, OWN_SENDER, OWN_RECIPIENT, NONE }

    /** Um componente de valor cobrado no CT-e (código canônico, valores do mesmo código somados pelo motor). */
    public record ChargedComponent(String code, BigDecimal amount) {
    }

    /**
     * @param relatedCteKey    CT-e complementado ou substituído
     * @param takerIsTenant    o tomador do serviço é o tenant (CNPJ do tomador pertence ao tenant)
     * @param chargedAmount    valor cobrado ({@code vTPrest})
     * @param actualWeightKg   peso real declarado no CT-e (nulo se ausente)
     * @param cargoValue       valor da carga declarado no CT-e (nulo se ausente)
     */
    public record Cte(String key, CteType type, String relatedCteKey, String carrierCnpj, boolean takerIsTenant,
            BigDecimal chargedAmount, List<ChargedComponent> components, BigDecimal actualWeightKg,
            BigDecimal cargoValue) {
    }

    /** NF-e recebida e vinculada ao CT-e. */
    public record Nfe(String key, BigDecimal grossWeightKg, BigDecimal totalAmount, FreightMode freightMode,
            String carrierCnpj, boolean cancelled) {
    }

    /** Valor cotado: soma das cotações aplicáveis das NF-es (só existe quando todas têm cotação). */
    public record Quote(BigDecimal total, List<String> quoteIds) {
    }

    /** Resultado da seleção da tabela e do cálculo do frete. */
    public sealed interface Freight permits NoTable, RouteNotCovered, Calculated {
    }

    public record NoTable() implements Freight {
    }

    public record RouteNotCovered(String tableId, int tableVersion) implements Freight {
    }

    public record Calculated(String tableId, int tableVersion, BigDecimal total, List<ChargedComponent> components)
            implements Freight {
    }

    /** CT-e anterior (caso criado antes) que referencia uma das mesmas NF-es. */
    public record EarlierCte(String cteKey, String caseId, String nfeKey, String carrierRoot, boolean valid) {
    }
}
