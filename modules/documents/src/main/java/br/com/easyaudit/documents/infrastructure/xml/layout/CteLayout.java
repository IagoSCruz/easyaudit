package br.com.easyaudit.documents.infrastructure.xml.layout;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Subconjunto do layout do CT-e 4.00 ({@code cteProc}) necessário ao modelo canônico. Os nomes dos componentes são os
 * dos elementos XML. Elementos desconhecidos (grupos novos de notas técnicas) são ignorados.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CteLayout(CTe CTe, ProtCTe protCTe) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTe(InfCte infCte) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfCte(@JacksonXmlProperty(isAttribute = true) String Id,
            @JacksonXmlProperty(isAttribute = true) String versao,
            Ide ide, Party emit, Party rem, Party exped, Party receb, Party dest, VPrest vPrest, Imp imp,
            InfCTeNorm infCTeNorm, InfCteComp infCteComp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ide(String mod, String serie, String nCT, String dhEmi, String tpAmb, String tpCTe,
            String cMunIni, String xMunIni, String UFIni, String cMunFim, String xMunFim, String UFFim,
            Toma3 toma3, Toma4 toma4) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Toma3(String toma) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Toma4(String toma, String CNPJ, String CPF, String xNome, Address enderToma) {
    }

    /** Remetente, expedidor, recebedor, destinatário ou emitente. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Party(String CNPJ, String CPF, String xNome, Address enderEmit, Address enderReme, Address enderExped,
            Address enderReceb, Address enderDest) {

        /** O endereço do grupo, qualquer que seja o nome do elemento (varia por papel). */
        public Address address() {
            if (enderEmit != null) return enderEmit;
            if (enderReme != null) return enderReme;
            if (enderExped != null) return enderExped;
            if (enderReceb != null) return enderReceb;
            return enderDest;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String cMun, String xMun, String CEP, String UF) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VPrest(String vTPrest, String vRec, List<Comp> Comp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Comp(String xNome, String vComp) {
    }

    /** Qualquer variante de ICMS (ICMS00, ICMS20, ICMS45, ICMS60, ICMS90, ICMSOutraUF, ICMSSN). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Imp(Icms ICMS) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Icms(IcmsData ICMS00, IcmsData ICMS20, IcmsData ICMS45, IcmsData ICMS60, IcmsData ICMS90,
            IcmsData ICMSOutraUF, IcmsData ICMSSN) {

        public IcmsData data() {
            for (IcmsData d : new IcmsData[] {ICMS00, ICMS20, ICMS45, ICMS60, ICMS90, ICMSOutraUF, ICMSSN}) {
                if (d != null) return d;
            }
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IcmsData(String CST, String vBC, String pICMS, String vICMS, String vBCOutraUF, String pICMSOutraUF,
            String vICMSOutraUF) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfCTeNorm(InfCarga infCarga, InfDoc infDoc, InfCteSub infCteSub) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfCarga(String vCarga, List<InfQ> infQ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfQ(String cUnid, String tpMed, String qCarga) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfDoc(List<InfNFe> infNFe) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfNFe(String chave) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfCteSub(String chCte) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfCteComp(String chCTe) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProtCTe(InfProt infProt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfProt(String cStat, String nProt) {
    }
}
