package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.Security;

import br.com.easyaudit.documents.domain.DocumentType;
import br.com.easyaudit.documents.fixtures.CancellationFixture;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.documents.fixtures.TestCertificate;
import br.com.easyaudit.documents.fixtures.XmlSigner;
import br.com.easyaudit.documents.infrastructure.xml.InvalidSignatureException;
import br.com.easyaudit.documents.infrastructure.xml.MalformedXmlException;
import br.com.easyaudit.documents.infrastructure.xml.SignatureVerifier;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class SignatureVerifierTest {

    @Test
    void acceptsValidSha1SignaturesOfAllDocumentTypes() {
        FiscalXml cte = CteFixture.builder().build();
        assertThatCode(() -> SignatureVerifier.verify(cte.bytes(), DocumentType.CTE)).doesNotThrowAnyException();
        assertThatCode(() -> SignatureVerifier.verify(NfeFixture.builder().build().bytes(), DocumentType.NFE))
                .doesNotThrowAnyException();
        assertThatCode(() -> SignatureVerifier.verify(
                CancellationFixture.forCte(cte.accessKey(), "11222333000181").build().bytes(),
                DocumentType.CTE_CANCELLATION)).doesNotThrowAnyException();
        assertThatCode(() -> SignatureVerifier.verify(
                CancellationFixture.forNfe(NfeFixture.builder().build().accessKey(), "45723174000110").build().bytes(),
                DocumentType.NFE_CANCELLATION)).doesNotThrowAnyException();
    }

    @Test
    void policyOnlyRelaxesSha1AndKeepsOtherRestrictions() {
        String policy = Security.getProperty("jdk.xml.dsig.secureValidationPolicy");
        assertThat(policy).doesNotContain("xmldsig#sha1,").doesNotContain("xmldsig#rsa-sha1")
                .contains("xmldsig-more#rsa-md5").contains("xmldsig-more#md5").contains("xslt-19991116")
                .contains("maxTransforms").contains("maxReferences").contains("minKeySize RSA 1024")
                .contains("noDuplicateIds").contains("xmldsig#dsa-sha1").contains("ecdsa-sha1");
    }

    @Test
    void rejectsTamperedContent() {
        FiscalXml tampered = CteFixture.builder().build().replacing("<vTPrest>152.37</vTPrest>", "<vTPrest>1.00</vTPrest>");
        assertThatThrownBy(() -> SignatureVerifier.verify(tampered.bytes(), DocumentType.CTE))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsMissingSignature() {
        assertThatThrownBy(() -> SignatureVerifier.verify(CteFixture.builder().unsigned().build().bytes(), DocumentType.CTE))
                .isInstanceOf(InvalidSignatureException.class).hasMessageContaining("assinatura");
    }

    @Test
    void rejectsSignatureWrapping() {
        FiscalXml original = CteFixture.builder().build();
        var doc = XmlSigner.parse(original.bytes());
        Element infCte = (Element) doc.getElementsByTagNameNS("*", "infCte").item(0);
        Element cte = (Element) infCte.getParentNode();
        // esconde uma cópia do elemento assinado (com o Id original) em outro ponto do XML...
        Element hidden = (Element) infCte.cloneNode(true);
        Element wrapper = doc.createElementNS(cte.getNamespaceURI(), "Object");
        wrapper.appendChild(hidden);
        cte.appendChild(wrapper);
        // ...e forja o elemento que a aplicação lê, com outro Id e outro valor
        infCte.setAttribute("Id", "CTe" + "9".repeat(44));
        infCte.getElementsByTagNameNS("*", "vTPrest").item(0).setTextContent("1.00");
        byte[] attack = XmlSigner.serialize(doc);

        assertThatThrownBy(() -> SignatureVerifier.verify(attack, DocumentType.CTE))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsSignatureOfAnotherElementWithoutDuplicates() {
        // a assinatura referencia um Id que não é o do infCte esperado
        FiscalXml original = CteFixture.builder().build();
        String key = original.accessKey();
        FiscalXml renamed = original.replacing("Id=\"CTe" + key + "\"", "Id=\"CTe" + "8".repeat(44) + "\"");
        assertThatThrownBy(() -> SignatureVerifier.verify(renamed.bytes(), DocumentType.CTE))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsTwoSignatures() {
        var doc = XmlSigner.parse(CteFixture.builder().build().bytes());
        Element signature = (Element) doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature").item(0);
        signature.getParentNode().appendChild(signature.cloneNode(true));
        assertThatThrownBy(() -> SignatureVerifier.verify(XmlSigner.serialize(doc), DocumentType.CTE))
                .isInstanceOf(InvalidSignatureException.class).hasMessageContaining("exatamente uma assinatura");
    }

    @Test
    void acceptsAnyCertificateBecauseTheChainIsNotValidated() {
        // a cadeia ICP-Brasil não é validada nesta change: o que se garante é a integridade, não a identidade
        FiscalXml other = CteFixture.builder().certificate(TestCertificate.generate("CN=OUTRO")).build();
        assertThatCode(() -> SignatureVerifier.verify(other.bytes(), DocumentType.CTE)).doesNotThrowAnyException();
    }

    @Test
    void rejectsXmlWithDoctype() {
        byte[] xxe = ("<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x \"y\">]><cteProc/>").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SignatureVerifier.verify(xxe, DocumentType.CTE)).isInstanceOf(MalformedXmlException.class);
    }
}
