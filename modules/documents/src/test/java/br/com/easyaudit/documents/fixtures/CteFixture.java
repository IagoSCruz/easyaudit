package br.com.easyaudit.documents.fixtures;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Gera um CT-e 4.00 processado ({@code cteProc}) assinado com RSA-SHA1, como o das transportadoras. */
public final class CteFixture {

    public enum Type { NORMAL, COMPLEMENT, SUBSTITUTE }

    /** Quem é o tomador do serviço (grupo {@code toma3}: 0 a 3; {@code toma4}: outro). */
    public enum Taker { SENDER, DISPATCHER, RECEIVER, RECIPIENT, OTHER }

    public record Component(String name, String amount) {
    }

    public record Quantity(String unitCode, String measureType, String quantity) {
    }

    // padrões: carrier 11.222.333/0001-81 emite para a loja 45.723.174/0001-10
    private String issuerCnpj = "11222333000181";
    private String issuerName = "TRANSPORTADORA EXEMPLO LTDA";
    private String senderCnpj = Cnpjs.of("45723174", "0001");
    private String dispatcherCnpj;
    private String receiverCnpj;
    private String recipientCnpj = Cnpjs.of("99888777", "0001");
    private String otherTakerCnpj;
    private Taker taker = Taker.SENDER;
    private OffsetDateTime issuedAt = OffsetDateTime.parse("2026-10-10T10:15:30-03:00");
    private int series = 1;
    private int number = 123;
    private String randomCode = "12345678";
    private Type type = Type.NORMAL;
    private String relatedKey;
    private String originIbge = "3550308";
    private String originCity = "Sao Paulo";
    private String originUf = "SP";
    private String destinationIbge = "3304557";
    private String destinationCity = "Rio de Janeiro";
    private String destinationUf = "RJ";
    private String destinationCep = "20040020";
    private String totalAmount = "152.37";
    private String receivableAmount;
    private final List<Component> components = new ArrayList<>();
    private String icmsCst = "00";
    private String icmsBase = "152.37";
    private String icmsRate = "12.00";
    private String icmsAmount = "18.28";
    private String cargoValue = "5000.00";
    private final List<Quantity> quantities = new ArrayList<>();
    private final List<String> nfeKeys = new ArrayList<>();
    private int environment = 1;
    private String version = "4.00";
    private String protocolStatus = "100";
    private boolean withProtocol = true;
    private boolean withValuesGroup = true;
    private boolean withoutQuantities;
    private boolean unknownTaxGroup;
    private boolean signed = true;
    private TestCertificate certificate = TestCertificate.shared();

    private CteFixture() {
    }

    public static CteFixture builder() {
        return new CteFixture();
    }

    public CteFixture issuerCnpj(String v) { this.issuerCnpj = v; return this; }
    public CteFixture senderCnpj(String v) { this.senderCnpj = v; return this; }
    public CteFixture dispatcherCnpj(String v) { this.dispatcherCnpj = v; return this; }
    public CteFixture receiverCnpj(String v) { this.receiverCnpj = v; return this; }
    public CteFixture recipientCnpj(String v) { this.recipientCnpj = v; return this; }
    public CteFixture taker(Taker t) { this.taker = t; return this; }
    /** Tomador "outros" (grupo {@code toma4}) com o CNPJ informado. */
    public CteFixture otherTaker(String cnpj) { this.taker = Taker.OTHER; this.otherTakerCnpj = cnpj; return this; }
    public CteFixture issuedAt(OffsetDateTime v) { this.issuedAt = v; return this; }
    public CteFixture series(int v) { this.series = v; return this; }
    public CteFixture number(int v) { this.number = v; return this; }
    public CteFixture randomCode(String v) { this.randomCode = v; return this; }
    public CteFixture type(Type v) { this.type = v; return this; }
    /** Chave do CT-e complementado ou substituído. */
    public CteFixture relatedKey(String v) { this.relatedKey = v; return this; }
    public CteFixture destination(String ibge, String city, String uf, String cep) {
        this.destinationIbge = ibge; this.destinationCity = city; this.destinationUf = uf; this.destinationCep = cep;
        return this;
    }
    public CteFixture origin(String ibge, String city, String uf) {
        this.originIbge = ibge; this.originCity = city; this.originUf = uf;
        return this;
    }
    public CteFixture totalAmount(String v) { this.totalAmount = v; return this; }
    public CteFixture receivableAmount(String v) { this.receivableAmount = v; return this; }
    public CteFixture component(String name, String amount) { components.add(new Component(name, amount)); return this; }
    public CteFixture icms(String cst, String base, String rate, String amount) {
        this.icmsCst = cst; this.icmsBase = base; this.icmsRate = rate; this.icmsAmount = amount;
        return this;
    }
    public CteFixture cargoValue(String v) { this.cargoValue = v; return this; }
    public CteFixture quantity(String unitCode, String measureType, String qty) {
        quantities.add(new Quantity(unitCode, measureType, qty));
        return this;
    }
    public CteFixture nfeKey(String v) { nfeKeys.add(v); return this; }
    /** {@code tpAmb}: 1 produção, 2 homologação. */
    public CteFixture environment(int v) { this.environment = v; return this; }
    public CteFixture version(String v) { this.version = v; return this; }
    public CteFixture protocolStatus(String cStat) { this.protocolStatus = cStat; return this; }
    public CteFixture withoutProtocol() { this.withProtocol = false; return this; }
    /** CT-e sem nenhuma quantidade de carga ({@code infQ}): sem peso real, peso cubado nem volume. */
    public CteFixture withoutQuantities() { this.withoutQuantities = true; return this; }
    public CteFixture withoutValuesGroup() { this.withValuesGroup = false; return this; }
    /** Inclui um grupo de tributos que o modelo canônico desconhece (como nas notas técnicas novas). */
    public CteFixture unknownTaxGroup() { this.unknownTaxGroup = true; return this; }
    public CteFixture unsigned() { this.signed = false; return this; }
    public CteFixture certificate(TestCertificate c) { this.certificate = c; return this; }

    /** A chave de acesso que o documento terá. */
    public String accessKey() {
        return Keys.build("35", issuedAt.format(DateTimeFormatter.ofPattern("yyMM")), issuerCnpj, "57", series, number,
                randomCode);
    }

    public FiscalXml build() {
        String key = accessKey();
        String xml = xml(key);
        if (!signed) {
            return new FiscalXml(XmlSigner.utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml), key);
        }
        Document doc = XmlSigner.parse(XmlSigner.utf8(xml));
        Element info = (Element) doc.getElementsByTagNameNS("*", "infCte").item(0);
        Element cte = (Element) doc.getElementsByTagNameNS("*", "CTe").item(0);
        XmlSigner.sign(doc, info, cte, certificate);
        return new FiscalXml(XmlSigner.serialize(doc), key);
    }

    private String party(String element, String addressElement, String cnpj, String name, String cep) {
        return "<" + element + ">" + Xml.tag("CNPJ", cnpj) + Xml.tag("IE", "111111111111") + Xml.tag("xNome", name)
                + "<" + addressElement + "><xLgr>RUA EXEMPLO</xLgr><nro>100</nro><xBairro>CENTRO</xBairro>"
                + "<cMun>" + destinationIbge + "</cMun><xMun>" + destinationCity + "</xMun><CEP>" + cep + "</CEP><UF>"
                + destinationUf + "</UF></" + addressElement + "></" + element + ">";
    }

    private String xml(String key) {
        String cDv = key.substring(43);
        int tpCte = switch (type) { case NORMAL -> 0; case COMPLEMENT -> 1; case SUBSTITUTE -> 3; };
        StringBuilder sb = new StringBuilder();
        sb.append("<cteProc xmlns=\"http://www.portalfiscal.inf.br/cte\" versao=\"").append(version).append("\">");
        sb.append("<CTe><infCte Id=\"CTe").append(key).append("\" versao=\"").append(version).append("\">");
        sb.append("<ide><cUF>35</cUF><cCT>").append(randomCode).append("</cCT><CFOP>6353</CFOP>")
                .append("<natOp>PRESTACAO DE SERVICO DE TRANSPORTE</natOp><mod>57</mod>")
                .append(Xml.tag("serie", String.valueOf(series))).append(Xml.tag("nCT", String.valueOf(number)))
                .append(Xml.tag("dhEmi", issuedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                .append("<tpImp>1</tpImp><tpEmis>1</tpEmis>").append(Xml.tag("cDV", cDv))
                .append(Xml.tag("tpAmb", String.valueOf(environment))).append(Xml.tag("tpCTe", String.valueOf(tpCte)))
                .append("<procEmi>0</procEmi><verProc>1.0</verProc>")
                .append(Xml.tag("cMunEnv", originIbge)).append(Xml.tag("xMunEnv", originCity))
                .append(Xml.tag("UFEnv", originUf)).append("<modal>01</modal><tpServ>0</tpServ>")
                .append(Xml.tag("cMunIni", originIbge)).append(Xml.tag("xMunIni", originCity))
                .append(Xml.tag("UFIni", originUf)).append(Xml.tag("cMunFim", destinationIbge))
                .append(Xml.tag("xMunFim", destinationCity)).append(Xml.tag("UFFim", destinationUf))
                .append("<retira>1</retira><indIEToma>1</indIEToma>");
        if (taker == Taker.OTHER) {
            sb.append("<toma4><toma>4</toma>").append(Xml.tag("CNPJ", otherTakerCnpj)).append("<IE>111111111111</IE>")
                    .append("<xNome>TOMADOR OUTROS</xNome><enderToma><xLgr>RUA</xLgr><nro>1</nro><xBairro>B</xBairro>")
                    .append("<cMun>").append(destinationIbge).append("</cMun><xMun>").append(destinationCity)
                    .append("</xMun><CEP>").append(destinationCep).append("</CEP><UF>").append(destinationUf)
                    .append("</UF></enderToma></toma4>");
        } else {
            sb.append("<toma3><toma>").append(taker.ordinal()).append("</toma></toma3>");
        }
        sb.append("</ide>");
        sb.append("<emit>").append(Xml.tag("CNPJ", issuerCnpj)).append("<IE>222222222222</IE>")
                .append(Xml.tag("xNome", issuerName)).append("<enderEmit><xLgr>AV TRANSPORTE</xLgr><nro>1</nro>")
                .append("<xBairro>INDUSTRIAL</xBairro><cMun>").append(originIbge).append("</cMun><xMun>")
                .append(originCity).append("</xMun><CEP>01310100</CEP><UF>").append(originUf)
                .append("</UF></enderEmit></emit>");
        sb.append(party("rem", "enderReme", senderCnpj, "REMETENTE LOJA", "01310100"));
        if (dispatcherCnpj != null) {
            sb.append(party("exped", "enderExped", dispatcherCnpj, "EXPEDIDOR", "01310100"));
        }
        if (receiverCnpj != null) {
            sb.append(party("receb", "enderReceb", receiverCnpj, "RECEBEDOR", destinationCep));
        }
        sb.append(party("dest", "enderDest", recipientCnpj, "DESTINATARIO CLIENTE", destinationCep));
        if (withValuesGroup) {
            sb.append("<vPrest>").append(Xml.tag("vTPrest", Xml.money(totalAmount)))
                    .append(Xml.tag("vRec", Xml.money(receivableAmount != null ? receivableAmount : totalAmount)));
            for (Component c : components.isEmpty()
                    ? List.of(new Component("FRETE PESO", "100.00"), new Component("GRIS", "52.37")) : components) {
                sb.append("<Comp>").append(Xml.tag("xNome", c.name())).append(Xml.tag("vComp", Xml.money(c.amount())))
                        .append("</Comp>");
            }
            sb.append("</vPrest>");
        }
        sb.append("<imp><ICMS><ICMS00>").append(Xml.tag("CST", icmsCst)).append(Xml.tag("vBC", Xml.money(icmsBase)))
                .append(Xml.tag("pICMS", Xml.money(icmsRate))).append(Xml.tag("vICMS", Xml.money(icmsAmount)))
                .append("</ICMS00></ICMS>");
        if (unknownTaxGroup) {
            sb.append("<infTribFed><vPIS>1.00</vPIS><vCOFINS>4.50</vCOFINS><novoCampoDaNotaTecnica>X</novoCampoDaNotaTecnica></infTribFed>");
        }
        sb.append("</imp>");
        if (type == Type.COMPLEMENT) {
            sb.append("<infCteComp>").append(Xml.tag("chCTe", relatedKey)).append("</infCteComp>");
        } else {
            sb.append("<infCTeNorm><infCarga>").append(Xml.tag("vCarga", Xml.money(cargoValue)))
                    .append("<proPred>MERCADORIAS</proPred>");
            for (Quantity q : quantities.isEmpty() && !withoutQuantities ? List.of(new Quantity("01", "PESO BRUTO", "12.5")) : quantities) {
                sb.append("<infQ>").append(Xml.tag("cUnid", q.unitCode())).append(Xml.tag("tpMed", q.measureType()))
                        .append(Xml.tag("qCarga", Xml.qty(q.quantity()))).append("</infQ>");
            }
            sb.append("</infCarga>");
            List<String> keys = nfeKeys.isEmpty()
                    ? List.of(Keys.build("35", "2610", senderCnpj, "55", 1, 9001, "87654321")) : nfeKeys;
            sb.append("<infDoc>");
            keys.forEach(k -> sb.append("<infNFe>").append(Xml.tag("chave", k)).append("</infNFe>"));
            sb.append("</infDoc>");
            if (type == Type.SUBSTITUTE) {
                sb.append("<infCteSub>").append(Xml.tag("chCte", relatedKey)).append("</infCteSub>");
            }
            sb.append("</infCTeNorm>");
        }
        sb.append("</infCte></CTe>");
        if (withProtocol) {
            sb.append("<protCTe versao=\"").append(version).append("\"><infProt>")
                    .append(Xml.tag("tpAmb", String.valueOf(environment))).append("<verAplic>SP_CTE_PL_400</verAplic>")
                    .append(Xml.tag("chCTe", key)).append("<dhRecbto>2026-10-10T10:16:00-03:00</dhRecbto>")
                    .append("<nProt>135260000000001</nProt><digVal>abc=</digVal>")
                    .append(Xml.tag("cStat", protocolStatus)).append("<xMotivo>Autorizado o uso do CT-e</xMotivo>")
                    .append("</infProt></protCTe>");
        }
        sb.append("</cteProc>");
        return sb.toString();
    }
}
