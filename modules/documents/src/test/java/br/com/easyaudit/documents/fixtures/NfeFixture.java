package br.com.easyaudit.documents.fixtures;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Gera uma NF-e 4.00 processada ({@code nfeProc}) assinada com RSA-SHA1. */
public final class NfeFixture {

    public record Volume(int quantity, String netWeight, String grossWeight) {
    }

    private String issuerCnpj = Cnpjs.of("45723174", "0001");
    private String issuerName = "LOJA EXEMPLO LTDA";
    private String recipientCnpj = Cnpjs.of("99888777", "0001");
    private String recipientCpf;
    private String recipientName = "CLIENTE FINAL";
    private String recipientCep = "20040020";
    private String recipientIbge = "3304557";
    private String recipientCity = "Rio de Janeiro";
    private String recipientUf = "RJ";
    private OffsetDateTime issuedAt = OffsetDateTime.parse("2026-10-09T09:00:00-03:00");
    private int series = 1;
    private int number = 9001;
    private String randomCode = "87654321";
    private String totalAmount = "5000.00";
    private String productsAmount = "4950.00";
    private String freightAmount = "50.00";
    private int freightMode = 0;
    private String carrierCnpj = "11222333000181";
    private final List<Volume> volumes = new ArrayList<>();
    private String orderReference;
    private int environment = 1;
    private String version = "4.00";
    private String protocolStatus = "100";
    private boolean withProtocol = true;
    private boolean signed = true;
    private TestCertificate certificate = TestCertificate.shared();

    private NfeFixture() {
    }

    public static NfeFixture builder() {
        return new NfeFixture();
    }

    public NfeFixture issuerCnpj(String v) { this.issuerCnpj = v; return this; }
    public NfeFixture recipientCnpj(String v) { this.recipientCnpj = v; this.recipientCpf = null; return this; }
    public NfeFixture recipientCpf(String v) { this.recipientCpf = v; this.recipientCnpj = null; return this; }
    public NfeFixture recipientAddress(String cep, String ibge, String city, String uf) {
        this.recipientCep = cep; this.recipientIbge = ibge; this.recipientCity = city; this.recipientUf = uf;
        return this;
    }
    public NfeFixture issuedAt(OffsetDateTime v) { this.issuedAt = v; return this; }
    public NfeFixture series(int v) { this.series = v; return this; }
    public NfeFixture number(int v) { this.number = v; return this; }
    public NfeFixture randomCode(String v) { this.randomCode = v; return this; }
    public NfeFixture amounts(String total, String products, String freight) {
        this.totalAmount = total; this.productsAmount = products; this.freightAmount = freight;
        return this;
    }
    /** {@code modFrete}: 0 emitente (CIF), 1 destinatário (FOB), 2 terceiros, 3 próprio remetente, 4 próprio destinatário, 9 sem frete. */
    public NfeFixture freightMode(int v) { this.freightMode = v; return this; }
    public NfeFixture carrierCnpj(String v) { this.carrierCnpj = v; return this; }
    public NfeFixture volume(int quantity, String netWeight, String grossWeight) {
        volumes.add(new Volume(quantity, netWeight, grossWeight));
        return this;
    }
    /** Referência de pedido ({@code xPed}) do primeiro item. */
    public NfeFixture orderReference(String v) { this.orderReference = v; return this; }
    public NfeFixture environment(int v) { this.environment = v; return this; }
    public NfeFixture version(String v) { this.version = v; return this; }
    public NfeFixture protocolStatus(String cStat) { this.protocolStatus = cStat; return this; }
    public NfeFixture withoutProtocol() { this.withProtocol = false; return this; }
    public NfeFixture unsigned() { this.signed = false; return this; }
    public NfeFixture certificate(TestCertificate c) { this.certificate = c; return this; }

    public String accessKey() {
        return Keys.build("35", issuedAt.format(DateTimeFormatter.ofPattern("yyMM")), issuerCnpj, "55", series, number,
                randomCode);
    }

    public FiscalXml build() {
        String key = accessKey();
        String xml = xml(key);
        if (!signed) {
            return new FiscalXml(XmlSigner.utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml), key);
        }
        Document doc = XmlSigner.parse(XmlSigner.utf8(xml));
        Element info = (Element) doc.getElementsByTagNameNS("*", "infNFe").item(0);
        Element nfe = (Element) doc.getElementsByTagNameNS("*", "NFe").item(0);
        XmlSigner.sign(doc, info, nfe, certificate);
        return new FiscalXml(XmlSigner.serialize(doc), key);
    }

    private String xml(String key) {
        StringBuilder sb = new StringBuilder();
        sb.append("<nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"").append(version).append("\">");
        sb.append("<NFe><infNFe Id=\"NFe").append(key).append("\" versao=\"").append(version).append("\">");
        sb.append("<ide><cUF>35</cUF><cNF>").append(randomCode).append("</cNF><natOp>VENDA</natOp><mod>55</mod>")
                .append(Xml.tag("serie", String.valueOf(series))).append(Xml.tag("nNF", String.valueOf(number)))
                .append(Xml.tag("dhEmi", issuedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                .append("<tpNF>1</tpNF><idDest>2</idDest><cMunFG>3550308</cMunFG><tpImp>1</tpImp><tpEmis>1</tpEmis>")
                .append(Xml.tag("cDV", key.substring(43))).append(Xml.tag("tpAmb", String.valueOf(environment)))
                .append("<finNFe>1</finNFe><indFinal>1</indFinal><indPres>2</indPres><procEmi>0</procEmi>")
                .append("<verProc>1.0</verProc></ide>");
        sb.append("<emit>").append(Xml.tag("CNPJ", issuerCnpj)).append(Xml.tag("xNome", issuerName))
                .append("<enderEmit><xLgr>RUA LOJA</xLgr><nro>10</nro><xBairro>CENTRO</xBairro><cMun>3550308</cMun>")
                .append("<xMun>Sao Paulo</xMun><UF>SP</UF><CEP>01310100</CEP></enderEmit><IE>333333333333</IE></emit>");
        sb.append("<dest>")
                .append(recipientCnpj != null ? Xml.tag("CNPJ", recipientCnpj) : Xml.tag("CPF", recipientCpf))
                .append(Xml.tag("xNome", recipientName))
                .append("<enderDest><xLgr>RUA CLIENTE</xLgr><nro>20</nro><xBairro>BAIRRO</xBairro>")
                .append(Xml.tag("cMun", recipientIbge)).append(Xml.tag("xMun", recipientCity))
                .append(Xml.tag("UF", recipientUf)).append(Xml.tag("CEP", recipientCep)).append("</enderDest>")
                .append("<indIEDest>9</indIEDest></dest>");
        sb.append("<det nItem=\"1\"><prod><cProd>SKU1</cProd><xProd>PRODUTO</xProd><NCM>12345678</NCM><CFOP>6102</CFOP>")
                .append("<uCom>UN</uCom><qCom>1.0000</qCom><vUnCom>4950.00</vUnCom>")
                .append(Xml.tag("vProd", Xml.money(productsAmount)));
        if (orderReference != null) {
            sb.append(Xml.tag("xPed", orderReference));
        }
        sb.append("</prod></det>");
        sb.append("<total><ICMSTot>").append(Xml.tag("vProd", Xml.money(productsAmount)))
                .append(Xml.tag("vFrete", Xml.money(freightAmount))).append(Xml.tag("vNF", Xml.money(totalAmount)))
                .append("</ICMSTot></total>");
        sb.append("<transp>").append(Xml.tag("modFrete", String.valueOf(freightMode)));
        if (carrierCnpj != null) {
            sb.append("<transporta>").append(Xml.tag("CNPJ", carrierCnpj)).append("<xNome>TRANSPORTADORA EXEMPLO</xNome></transporta>");
        }
        for (Volume v : volumes) {
            sb.append("<vol>").append(Xml.tag("qVol", String.valueOf(v.quantity()))).append("<esp>CAIXA</esp>")
                    .append(v.netWeight() == null ? "" : Xml.tag("pesoL", new java.math.BigDecimal(v.netWeight()).setScale(3).toPlainString()))
                    .append(v.grossWeight() == null ? "" : Xml.tag("pesoB", new java.math.BigDecimal(v.grossWeight()).setScale(3).toPlainString()))
                    .append("</vol>");
        }
        sb.append("</transp>");
        sb.append("</infNFe></NFe>");
        if (withProtocol) {
            sb.append("<protNFe versao=\"").append(version).append("\"><infProt>")
                    .append(Xml.tag("tpAmb", String.valueOf(environment))).append("<verAplic>SP_NFE_PL_009</verAplic>")
                    .append(Xml.tag("chNFe", key)).append("<dhRecbto>2026-10-09T09:01:00-03:00</dhRecbto>")
                    .append("<nProt>135260000000002</nProt><digVal>abc=</digVal>")
                    .append(Xml.tag("cStat", protocolStatus)).append("<xMotivo>Autorizado o uso da NF-e</xMotivo>")
                    .append("</infProt></protNFe>");
        }
        sb.append("</nfeProc>");
        return sb.toString();
    }
}
