package br.com.easyaudit.documents.fixtures;

/** Eventos processados de cancelamento ({@code procEventoCTe} e {@code procEventoNFe}, {@code tpEvento} 110111). */
public final class CancellationFixture {

    private final boolean cte;
    private final String documentKey;
    private String authorCnpj;
    private int sequence = 1;
    private String eventType = "110111";
    private String registrationStatus = "135";
    private boolean withRegistration = true;
    private int environment = 1;
    private boolean signed = true;
    private TestCertificate certificate = TestCertificate.shared();

    private CancellationFixture(boolean cte, String documentKey, String authorCnpj) {
        this.cte = cte;
        this.documentKey = documentKey;
        this.authorCnpj = authorCnpj;
    }

    public static CancellationFixture forCte(String cteKey, String authorCnpj) {
        return new CancellationFixture(true, cteKey, authorCnpj);
    }

    public static CancellationFixture forNfe(String nfeKey, String authorCnpj) {
        return new CancellationFixture(false, nfeKey, authorCnpj);
    }

    public CancellationFixture sequence(int v) { this.sequence = v; return this; }
    /** {@code tpEvento}; 110111 é cancelamento, 110110 é carta de correção. */
    public CancellationFixture eventType(String v) { this.eventType = v; return this; }
    public CancellationFixture registrationStatus(String v) { this.registrationStatus = v; return this; }
    public CancellationFixture withoutRegistration() { this.withRegistration = false; return this; }
    public CancellationFixture environment(int v) { this.environment = v; return this; }
    public CancellationFixture unsigned() { this.signed = false; return this; }

    public FiscalXml build() {
        String id = "ID" + eventType + documentKey + String.format(cte ? "%03d" : "%02d", sequence);
        String xml = xml(id);
        if (!signed) {
            return new FiscalXml(XmlSigner.utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml), documentKey);
        }
        var doc = XmlSigner.parse(XmlSigner.utf8(xml));
        var info = (org.w3c.dom.Element) doc.getElementsByTagNameNS("*", "infEvento").item(0);
        var event = (org.w3c.dom.Element) doc.getElementsByTagNameNS("*", cte ? "eventoCTe" : "evento").item(0);
        XmlSigner.sign(doc, info, event, certificate);
        return new FiscalXml(XmlSigner.serialize(doc), documentKey);
    }

    private String xml(String id) {
        String ns = cte ? "http://www.portalfiscal.inf.br/cte" : "http://www.portalfiscal.inf.br/nfe";
        String root = cte ? "procEventoCTe" : "procEventoNFe";
        String event = cte ? "eventoCTe" : "evento";
        String keyTag = cte ? "chCTe" : "chNFe";
        String version = cte ? "4.00" : "1.00";
        String nSeq = String.valueOf(sequence);
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(root).append(" xmlns=\"").append(ns).append("\" versao=\"").append(version).append("\">");
        sb.append("<").append(event).append(" versao=\"").append(version).append("\">");
        sb.append("<infEvento Id=\"").append(id).append("\"><cOrgao>35</cOrgao>")
                .append(Xml.tag("tpAmb", String.valueOf(environment))).append(Xml.tag("CNPJ", authorCnpj))
                .append(Xml.tag(keyTag, documentKey)).append("<dhEvento>2026-10-11T12:00:00-03:00</dhEvento>")
                .append(Xml.tag("tpEvento", eventType)).append(Xml.tag("nSeqEvento", nSeq));
        if (!cte) {
            sb.append("<verEvento>1.00</verEvento>");
        }
        sb.append("<detEvento versao=\"").append(version).append("\">");
        if (cte) {
            sb.append("<evCancCTe><descEvento>Cancelamento</descEvento><nProt>135260000000001</nProt>")
                    .append("<xJust>Cancelamento de teste do CT-e</xJust></evCancCTe>");
        } else {
            sb.append("<descEvento>Cancelamento</descEvento><nProt>135260000000002</nProt>")
                    .append("<xJust>Cancelamento de teste da NF-e</xJust>");
        }
        sb.append("</detEvento></infEvento></").append(event).append(">");
        if (withRegistration) {
            sb.append("<").append(cte ? "retEventoCTe" : "retEvento").append(" versao=\"").append(version).append("\">")
                    .append("<infEvento>").append(Xml.tag("tpAmb", String.valueOf(environment)))
                    .append("<verAplic>SP_EVENTO_PL</verAplic><cOrgao>35</cOrgao>")
                    .append(Xml.tag("cStat", registrationStatus)).append("<xMotivo>Evento registrado e vinculado</xMotivo>")
                    .append(Xml.tag(keyTag, documentKey)).append(Xml.tag("tpEvento", eventType))
                    .append(Xml.tag("nSeqEvento", nSeq)).append("<dhRegEvento>2026-10-11T12:00:05-03:00</dhRegEvento>")
                    .append("<nProt>135260000000009</nProt></infEvento></").append(cte ? "retEventoCTe" : "retEvento").append(">");
        }
        sb.append("</").append(root).append(">");
        return sb.toString();
    }
}
