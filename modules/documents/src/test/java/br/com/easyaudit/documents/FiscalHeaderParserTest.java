package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import br.com.easyaudit.documents.domain.DocumentType;
import br.com.easyaudit.documents.domain.FiscalHeader;
import br.com.easyaudit.documents.fixtures.CancellationFixture;
import br.com.easyaudit.documents.fixtures.Cnpjs;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.documents.infrastructure.xml.FiscalHeaderParser;
import br.com.easyaudit.documents.infrastructure.xml.MalformedXmlException;
import br.com.easyaudit.documents.infrastructure.xml.UnsupportedDocumentException;
import org.junit.jupiter.api.Test;

class FiscalHeaderParserTest {

    @Test
    void parsesCte() {
        var fixture = CteFixture.builder();
        FiscalHeader h = FiscalHeaderParser.parse(fixture.build().bytes());

        assertThat(h.type()).isEqualTo(DocumentType.CTE);
        assertThat(h.version()).isEqualTo("4.00");
        assertThat(h.environment()).isEqualTo(1);
        assertThat(h.accessKey()).isEqualTo(fixture.accessKey());
        assertThat(h.protocolStatus()).isEqualTo("100");
        assertThat(h.issuerCnpj()).isEqualTo("11222333000181");
        // padrão: toma3 = 0 (remetente)
        assertThat(h.takerCnpj()).isEqualTo(Cnpjs.of("45723174", "0001"));
    }

    @Test
    void resolvesTheTakerByToma3AndToma4() {
        String dispatcher = Cnpjs.of("10000001", "0001");
        String receiver = Cnpjs.of("10000002", "0001");
        String recipient = Cnpjs.of("10000003", "0001");
        String other = Cnpjs.of("10000004", "0001");

        assertThat(parse(CteFixture.builder().dispatcherCnpj(dispatcher).taker(CteFixture.Taker.DISPATCHER)).takerCnpj())
                .isEqualTo(dispatcher);
        assertThat(parse(CteFixture.builder().receiverCnpj(receiver).taker(CteFixture.Taker.RECEIVER)).takerCnpj())
                .isEqualTo(receiver);
        assertThat(parse(CteFixture.builder().recipientCnpj(recipient).taker(CteFixture.Taker.RECIPIENT)).takerCnpj())
                .isEqualTo(recipient);
        assertThat(parse(CteFixture.builder().otherTaker(other)).takerCnpj()).isEqualTo(other);
    }

    private static FiscalHeader parse(CteFixture fixture) {
        return FiscalHeaderParser.parse(fixture.build().bytes());
    }

    @Test
    void parsesNfe() {
        var fixture = NfeFixture.builder();
        FiscalHeader h = FiscalHeaderParser.parse(fixture.build().bytes());

        assertThat(h.type()).isEqualTo(DocumentType.NFE);
        assertThat(h.accessKey()).isEqualTo(fixture.accessKey());
        assertThat(h.issuerCnpj()).isEqualTo(Cnpjs.of("45723174", "0001"));
        assertThat(h.recipientCnpj()).isEqualTo(Cnpjs.of("99888777", "0001"));
        assertThat(h.protocolStatus()).isEqualTo("100");
        assertThat(h.takerCnpj()).isNull();
    }

    @Test
    void nfeToIndividualHasNoRecipientCnpj() {
        FiscalHeader h = FiscalHeaderParser.parse(NfeFixture.builder().recipientCpf("12345678909").build().bytes());
        assertThat(h.recipientCnpj()).isNull();
    }

    @Test
    void parsesCancellationEvents() {
        String cteKey = CteFixture.builder().build().accessKey();
        FiscalHeader cte = FiscalHeaderParser.parse(
                CancellationFixture.forCte(cteKey, "11222333000181").sequence(2).build().bytes());
        assertThat(cte.type()).isEqualTo(DocumentType.CTE_CANCELLATION);
        assertThat(cte.accessKey()).isEqualTo(cteKey);
        assertThat(cte.eventType()).isEqualTo("110111");
        assertThat(cte.eventSequence()).isEqualTo(2);
        assertThat(cte.protocolStatus()).isEqualTo("135");
        assertThat(cte.issuerCnpj()).isEqualTo("11222333000181");

        String nfeKey = NfeFixture.builder().build().accessKey();
        FiscalHeader nfe = FiscalHeaderParser.parse(
                CancellationFixture.forNfe(nfeKey, "45723174000110").build().bytes());
        assertThat(nfe.type()).isEqualTo(DocumentType.NFE_CANCELLATION);
        assertThat(nfe.accessKey()).isEqualTo(nfeKey);
    }

    @Test
    void readsHomologationAndMissingProtocol() {
        assertThat(parse(CteFixture.builder().environment(2)).isHomologation()).isTrue();
        FiscalHeader noProtocol = parse(CteFixture.builder().withoutProtocol());
        assertThat(noProtocol.protocolStatus()).isNull();
        assertThat(FiscalHeaderParser.parse(NfeFixture.builder().withoutProtocol().build().bytes()).protocolStatus()).isNull();
    }

    @Test
    void toleratesUnknownGroups() {
        assertThat(parse(CteFixture.builder().unknownTaxGroup()).type()).isEqualTo(DocumentType.CTE);
    }

    @Test
    void rejectsUnsupportedVersionsAndTypes() {
        assertThatThrownBy(() -> parse(CteFixture.builder().version("3.00")))
                .isInstanceOf(UnsupportedDocumentException.class).hasMessageContaining("3.00");
        assertThatThrownBy(() -> FiscalHeaderParser.parse(NfeFixture.builder().version("3.10").build().bytes()))
                .isInstanceOf(UnsupportedDocumentException.class);
        // carta de correção (110110) não é cancelamento
        assertThatThrownBy(() -> FiscalHeaderParser.parse(CancellationFixture
                .forCte(CteFixture.builder().build().accessKey(), "11222333000181").eventType("110110").build().bytes()))
                .isInstanceOf(UnsupportedDocumentException.class).hasMessageContaining("110110");
        assertThatThrownBy(() -> FiscalHeaderParser.parse(bytes("<MDFe xmlns=\"http://www.portalfiscal.inf.br/mdfe\"/>")))
                .isInstanceOf(UnsupportedDocumentException.class);
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> FiscalHeaderParser.parse(bytes("isto não é xml")))
                .isInstanceOf(MalformedXmlException.class);
        assertThatThrownBy(() -> FiscalHeaderParser.parse(bytes("<cteProc><CTe></cteProc>")))
                .isInstanceOf(MalformedXmlException.class);
        assertThatThrownBy(() -> FiscalHeaderParser.parse(new byte[0])).isInstanceOf(MalformedXmlException.class);
    }

    @Test
    void rejectsXxePayloads() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE cteProc [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<cteProc xmlns=\"http://www.portalfiscal.inf.br/cte\" versao=\"4.00\"><CTe><infCte Id=\"CTe1\" versao=\"4.00\">"
                + "<ide><mod>57</mod><xNome>&xxe;</xNome></ide></infCte></CTe></cteProc>";
        assertThatThrownBy(() -> FiscalHeaderParser.parse(bytes(xxe))).isInstanceOf(MalformedXmlException.class);

        String billionLaughs = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;\">]>"
                + "<cteProc>&lol2;</cteProc>";
        assertThatThrownBy(() -> FiscalHeaderParser.parse(bytes(billionLaughs))).isInstanceOf(MalformedXmlException.class);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
