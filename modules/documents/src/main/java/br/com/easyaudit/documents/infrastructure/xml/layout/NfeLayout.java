package br.com.easyaudit.documents.infrastructure.xml.layout;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/** Subconjunto do layout da NF-e 4.00 ({@code nfeProc}) necessário ao modelo canônico. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NfeLayout(NFe NFe, ProtNFe protNFe) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NFe(InfNFe infNFe) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfNFe(@JacksonXmlProperty(isAttribute = true) String Id,
            @JacksonXmlProperty(isAttribute = true) String versao,
            Ide ide, Emit emit, Dest dest, List<Det> det, Total total, Transp transp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ide(String mod, String serie, String nNF, String dhEmi, String tpAmb) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Emit(String CNPJ, String CPF, String xNome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dest(String CNPJ, String CPF, String xNome, EnderDest enderDest) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnderDest(String cMun, String xMun, String UF, String CEP) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Det(Prod prod) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Prod(String xPed, String vProd) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Total(IcmsTot ICMSTot) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IcmsTot(String vProd, String vFrete, String vNF) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transp(String modFrete, Transporta transporta, List<Vol> vol) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transporta(String CNPJ, String CPF, String xNome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vol(String qVol, String pesoL, String pesoB) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProtNFe(InfProt infProt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfProt(String cStat, String nProt) {
    }
}
