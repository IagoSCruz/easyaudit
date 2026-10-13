package br.com.easyaudit.documents.infrastructure.xml;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiFunction;

import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.contracts.api.model.CanonicalNfe;
import br.com.easyaudit.contracts.api.model.CargoQuantity;
import br.com.easyaudit.contracts.api.model.ComponentCode;
import br.com.easyaudit.contracts.api.model.CteComponent;
import br.com.easyaudit.contracts.api.model.CteIcms;
import br.com.easyaudit.contracts.api.model.CteTaker;
import br.com.easyaudit.contracts.api.model.CteType;
import br.com.easyaudit.contracts.api.model.FreightMode;
import br.com.easyaudit.contracts.api.model.NfeRecipient;
import br.com.easyaudit.contracts.api.model.Party;
import br.com.easyaudit.contracts.api.model.Place;
import br.com.easyaudit.contracts.api.model.TakerRole;
import br.com.easyaudit.contracts.api.model.Volume;
import br.com.easyaudit.documents.infrastructure.xml.layout.CteLayout;
import br.com.easyaudit.documents.infrastructure.xml.layout.NfeLayout;

/** Converte os DTOs de layout no modelo canônico do contrato (estável e independente do layout da SEFAZ). */
public final class CanonicalMapper {

    public static final int SCHEMA_VERSION = 1;

    private CanonicalMapper() {
    }

    /**
     * @param classifier classifica o nome livre de um componente de valor (recebe o CNPJ da transportadora e o nome)
     * @throws NormalizationException campo obrigatório ausente ou inválido
     */
    public static CanonicalCte toCte(CteLayout layout, BiFunction<String, String, ComponentCode> classifier) {
        var inf = required(layout.CTe() == null ? null : layout.CTe().infCte(), "infCte");
        var ide = required(inf.ide(), "ide");
        var emit = required(inf.emit(), "emit");
        var vPrest = required(inf.vPrest(), "vPrest (valores da prestação)");

        String key = required(inf.Id(), "infCte/@Id");
        if (!key.startsWith("CTe") || key.length() != 47) {
            throw new NormalizationException("Id do infCte inválido: " + key);
        }
        String carrierCnpj = required(emit.CNPJ(), "emit/CNPJ");

        CanonicalCte cte = new CanonicalCte();
        cte.setSchemaVersion(SCHEMA_VERSION);
        cte.setAccessKey(key.substring(3));
        cte.setNumber(required(ide.nCT(), "ide/nCT"));
        cte.setSeries(required(ide.serie(), "ide/serie"));
        cte.setIssuedAt(dateTime(required(ide.dhEmi(), "ide/dhEmi"), "ide/dhEmi"));
        cte.setType(cteType(ide.tpCTe()));
        cte.setCarrier(party(emit.CNPJ(), emit.CPF(), required(emit.xNome(), "emit/xNome")));
        if (inf.rem() != null) {
            cte.setSender(party(inf.rem().CNPJ(), inf.rem().CPF(), inf.rem().xNome()));
        }
        if (inf.dest() != null) {
            cte.setRecipient(party(inf.dest().CNPJ(), inf.dest().CPF(), inf.dest().xNome()));
        }
        cte.setTaker(taker(inf));
        cte.setOrigin(new Place(required(ide.UFIni(), "ide/UFIni")).ibgeCode(ide.cMunIni()).city(ide.xMunIni()));
        CteLayout.Address deliveryAddress = inf.receb() != null && inf.receb().address() != null
                ? inf.receb().address() : inf.dest() == null ? null : inf.dest().address();
        cte.setDestination(new Place(required(ide.UFFim(), "ide/UFFim")).ibgeCode(ide.cMunFim()).city(ide.xMunFim())
                .cep(deliveryAddress == null ? null : deliveryAddress.CEP()));
        cte.setTotalAmount(decimal(required(vPrest.vTPrest(), "vPrest/vTPrest"), "vPrest/vTPrest"));
        if (vPrest.vRec() != null) {
            cte.setReceivableAmount(decimal(vPrest.vRec(), "vPrest/vRec"));
        }

        List<CteComponent> components = new ArrayList<>();
        for (CteLayout.Comp comp : vPrest.Comp() == null ? List.<CteLayout.Comp>of() : vPrest.Comp()) {
            String name = required(comp.xNome(), "Comp/xNome");
            components.add(new CteComponent(name, classifier.apply(carrierCnpj, name),
                    decimal(required(comp.vComp(), "Comp/vComp"), "Comp/vComp")));
        }
        cte.setComponents(components);

        if (inf.imp() != null && inf.imp().ICMS() != null && inf.imp().ICMS().data() != null) {
            var icms = inf.imp().ICMS().data();
            CteIcms out = new CteIcms().cst(icms.CST());
            out.setBaseAmount(optionalDecimal(firstNonNull(icms.vBC(), icms.vBCOutraUF())));
            out.setRate(optionalDecimal(firstNonNull(icms.pICMS(), icms.pICMSOutraUF())));
            out.setAmount(optionalDecimal(firstNonNull(icms.vICMS(), icms.vICMSOutraUF())));
            cte.setIcms(out);
        }

        var norm = inf.infCTeNorm();
        if (norm != null && norm.infCarga() != null) {
            cte.setCargoValue(optionalDecimal(norm.infCarga().vCarga()));
            List<CargoQuantity> quantities = new ArrayList<>();
            for (CteLayout.InfQ q : norm.infCarga().infQ() == null ? List.<CteLayout.InfQ>of() : norm.infCarga().infQ()) {
                if (q.qCarga() != null) {
                    quantities.add(new CargoQuantity(q.cUnid(), q.tpMed(), decimal(q.qCarga(), "infQ/qCarga")));
                }
            }
            cte.setQuantities(quantities);
            cte.setMeasures(MeasureDeriver.derive(quantities));
        }

        LinkedHashSet<String> nfeKeys = new LinkedHashSet<>();
        if (norm != null && norm.infDoc() != null && norm.infDoc().infNFe() != null) {
            norm.infDoc().infNFe().forEach(n -> {
                if (n.chave() != null) {
                    nfeKeys.add(n.chave().trim());
                }
            });
        }
        cte.setReferencedNfeKeys(new ArrayList<>(nfeKeys));

        if (inf.infCteComp() != null && inf.infCteComp().chCTe() != null) {
            cte.setRelatedCteKey(inf.infCteComp().chCTe().trim());
        } else if (norm != null && norm.infCteSub() != null && norm.infCteSub().chCte() != null) {
            cte.setRelatedCteKey(norm.infCteSub().chCte().trim());
        }
        return cte;
    }

    public static CanonicalNfe toNfe(NfeLayout layout) {
        var inf = required(layout.NFe() == null ? null : layout.NFe().infNFe(), "infNFe");
        var ide = required(inf.ide(), "ide");
        var emit = required(inf.emit(), "emit");
        var dest = required(inf.dest(), "dest");
        var total = required(inf.total() == null ? null : inf.total().ICMSTot(), "total/ICMSTot");

        String key = required(inf.Id(), "infNFe/@Id");
        if (!key.startsWith("NFe") || key.length() != 47) {
            throw new NormalizationException("Id do infNFe inválido: " + key);
        }
        var address = required(dest.enderDest(), "dest/enderDest");

        CanonicalNfe nfe = new CanonicalNfe();
        nfe.setSchemaVersion(SCHEMA_VERSION);
        nfe.setAccessKey(key.substring(3));
        nfe.setNumber(required(ide.nNF(), "ide/nNF"));
        nfe.setSeries(required(ide.serie(), "ide/serie"));
        nfe.setIssuedAt(dateTime(required(ide.dhEmi(), "ide/dhEmi"), "ide/dhEmi"));
        nfe.setIssuer(party(emit.CNPJ(), emit.CPF(), required(emit.xNome(), "emit/xNome")));
        Place place = new Place(required(address.UF(), "enderDest/UF")).ibgeCode(address.cMun()).city(address.xMun())
                .cep(address.CEP());
        nfe.setRecipient(new NfeRecipient(party(dest.CNPJ(), dest.CPF(), required(dest.xNome(), "dest/xNome")), place));
        nfe.setTotalAmount(decimal(required(total.vNF(), "ICMSTot/vNF"), "ICMSTot/vNF"));
        nfe.setProductsAmount(optionalDecimal(total.vProd()));
        nfe.setFreightAmount(optionalDecimal(total.vFrete()));

        var transp = inf.transp();
        if (transp != null) {
            nfe.setFreightMode(freightMode(transp.modFrete()));
            if (transp.transporta() != null) {
                nfe.setCarrierCnpj(transp.transporta().CNPJ());
            }
        }
        List<Volume> volumes = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (NfeLayout.Vol v : transp == null || transp.vol() == null ? List.<NfeLayout.Vol>of() : transp.vol()) {
            Volume volume = new Volume();
            volume.setQuantity(v.qVol() == null ? null : Integer.valueOf(v.qVol().trim()));
            volume.setNetWeightKg(optionalDecimal(v.pesoL()));
            volume.setGrossWeightKg(optionalDecimal(v.pesoB()));
            volumes.add(volume);
            gross = gross.add(volume.getGrossWeightKg() == null ? BigDecimal.ZERO : volume.getGrossWeightKg());
            net = net.add(volume.getNetWeightKg() == null ? BigDecimal.ZERO : volume.getNetWeightKg());
        }
        nfe.setVolumes(volumes);
        nfe.setTotalGrossWeightKg(gross);
        nfe.setTotalNetWeightKg(net);

        LinkedHashSet<String> orders = new LinkedHashSet<>();
        if (inf.det() != null) {
            inf.det().forEach(d -> {
                if (d.prod() != null && d.prod().xPed() != null && !d.prod().xPed().isBlank()) {
                    orders.add(d.prod().xPed().trim());
                }
            });
        }
        nfe.setOrderReferences(new ArrayList<>(orders));
        return nfe;
    }

    // --- partes ---

    private static CteTaker taker(CteLayout.InfCte inf) {
        var ide = inf.ide();
        if (ide.toma4() != null) {
            var t = ide.toma4();
            return new CteTaker(party(t.CNPJ(), t.CPF(), required(t.xNome(), "toma4/xNome")), TakerRole.OTHER);
        }
        if (ide.toma3() == null || ide.toma3().toma() == null) {
            throw new NormalizationException("Campo obrigatório ausente: tomador do serviço (toma3/toma4)");
        }
        return switch (ide.toma3().toma().trim()) {
            case "0" -> new CteTaker(requiredParty(inf.rem(), "rem"), TakerRole.SENDER);
            case "1" -> new CteTaker(requiredParty(inf.exped(), "exped"), TakerRole.DISPATCHER);
            case "2" -> new CteTaker(requiredParty(inf.receb(), "receb"), TakerRole.RECEIVER);
            case "3" -> new CteTaker(requiredParty(inf.dest(), "dest"), TakerRole.RECIPIENT);
            default -> throw new NormalizationException("Tomador inválido: " + ide.toma3().toma());
        };
    }

    private static Party requiredParty(CteLayout.Party p, String name) {
        required(p, name);
        return party(p.CNPJ(), p.CPF(), required(p.xNome(), name + "/xNome"));
    }

    private static Party party(String cnpj, String cpf, String name) {
        Party party = new Party(name == null ? "" : name);
        party.setCnpj(cnpj);
        party.setCpf(cpf);
        return party;
    }

    private static CteType cteType(String tpCTe) {
        return switch (tpCTe == null ? "" : tpCTe.trim()) {
            case "0" -> CteType.NORMAL;
            case "1" -> CteType.COMPLEMENT;
            case "3" -> CteType.SUBSTITUTE;
            default -> throw new NormalizationException("Tipo de CT-e não suportado (tpCTe=" + tpCTe + ")");
        };
    }

    private static FreightMode freightMode(String modFrete) {
        return switch (modFrete == null ? "" : modFrete.trim()) {
            case "0" -> FreightMode.CIF;
            case "1" -> FreightMode.FOB;
            case "2" -> FreightMode.THIRD_PARTY;
            case "3" -> FreightMode.OWN_SENDER;
            case "4" -> FreightMode.OWN_RECIPIENT;
            case "9" -> FreightMode.NONE;
            default -> null;
        };
    }

    // --- utilitários ---

    private static <T> T required(T value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new NormalizationException("Campo obrigatório ausente: " + field);
        }
        return value;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new NormalizationException("Valor numérico inválido em " + field + ": " + value);
        }
    }

    private static BigDecimal optionalDecimal(String value) {
        return value == null || value.isBlank() ? null : decimal(value, "valor");
    }

    private static OffsetDateTime dateTime(String value, String field) {
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new NormalizationException("Data/hora inválida em " + field + ": " + value);
        }
    }
}
