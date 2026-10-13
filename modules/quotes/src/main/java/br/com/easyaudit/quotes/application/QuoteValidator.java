package br.com.easyaudit.quotes.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import br.com.easyaudit.quotes.application.QuoteValidationException.FieldIssue;
import br.com.easyaudit.quotes.domain.QuoteContent;
import br.com.easyaudit.shared.AccessKey;
import br.com.easyaudit.shared.Cnpj;

/** Valida e normaliza os campos de uma cotação, acumulando todos os erros. */
final class QuoteValidator {

    /** Dados brutos recebidos (REST ou webhook). */
    record Raw(String externalId, String carrierCnpj, String service, BigDecimal amount, OffsetDateTime quotedAt,
            String destinationCep, BigDecimal weightKg, BigDecimal cargoValue, Integer deliveryDays, String nfeKey,
            String orderReference) {
    }

    private QuoteValidator() {
    }

    static QuoteContent validate(Raw in) {
        List<FieldIssue> issues = new ArrayList<>();
        if (in.externalId() == null || in.externalId().isBlank()) {
            issues.add(new FieldIssue("externalId", "O id externo da cotação é obrigatório."));
        }
        String carrier = null;
        if (in.carrierCnpj() == null || !Cnpj.isValid(in.carrierCnpj())) {
            issues.add(new FieldIssue("carrierCnpj", "CNPJ da transportadora inválido."));
        } else {
            carrier = Cnpj.of(in.carrierCnpj()).value();
        }
        if (in.amount() == null || in.amount().signum() < 0) {
            issues.add(new FieldIssue("amount", "O valor cotado é obrigatório e não pode ser negativo."));
        }
        if (in.quotedAt() == null) {
            issues.add(new FieldIssue("quotedAt", "A data da cotação é obrigatória."));
        }
        String cep = null;
        if (in.destinationCep() != null) {
            String digits = in.destinationCep().replaceAll("[\\-.\\s]", "");
            if (digits.matches("[0-9]{8}")) {
                cep = digits;
            }
        }
        if (cep == null) {
            issues.add(new FieldIssue("destinationCep", "CEP de destino inválido (8 dígitos)."));
        }
        if (in.weightKg() != null && in.weightKg().signum() < 0) {
            issues.add(new FieldIssue("weightKg", "O peso não pode ser negativo."));
        }
        if (in.cargoValue() != null && in.cargoValue().signum() < 0) {
            issues.add(new FieldIssue("cargoValue", "O valor da carga não pode ser negativo."));
        }
        if (in.deliveryDays() != null && in.deliveryDays() < 0) {
            issues.add(new FieldIssue("deliveryDays", "O prazo não pode ser negativo."));
        }
        String nfeKey = blankToNull(in.nfeKey());
        if (nfeKey != null) {
            String compact = nfeKey.replaceAll("\\s", "");
            if (!AccessKey.isValid(compact) || !new AccessKey(compact).isNfe()) {
                issues.add(new FieldIssue("nfeKey", "Chave de NF-e inválida (44 dígitos, dígito verificador e modelo 55)."));
            } else {
                nfeKey = compact;
            }
        }
        String order = blankToNull(in.orderReference());
        if (nfeKey == null && order == null) {
            issues.add(new FieldIssue("nfeKey", "Informe a chave da NF-e ou a referência de pedido (ao menos um dos dois)."));
        }
        if (!issues.isEmpty()) {
            throw new QuoteValidationException(issues);
        }
        return new QuoteContent(carrier, blankToNull(in.service()), in.amount(), in.quotedAt(), cep, in.weightKg(),
                in.cargoValue(), in.deliveryDays(), nfeKey, order);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
