package br.com.easyaudit.documents.fixtures;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Assina elementos como os documentos fiscais reais: RSA-SHA1, C14N 1.0, transforms
 * {@code enveloped-signature} + C14N, {@code Reference/@URI} apontando para o {@code Id} do elemento assinado
 * e certificado X.509 no {@code KeyInfo}.
 */
public final class XmlSigner {

    static {
        // o XMLDSig lê a política de validação segura uma única vez; o ajuste de SHA-1 tem de vir antes de qualquer uso
        br.com.easyaudit.documents.infrastructure.xml.XmlDsigPolicy.install();
    }

    private XmlSigner() {
    }

    public static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] serialize(Document document) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Assina {@code signed} (que tem o atributo {@code Id}) e insere a {@code Signature} como último filho de
     * {@code parent}.
     */
    public static void sign(Document document, Element signed, Element parent, TestCertificate cert) {
        try {
            String id = signed.getAttribute("Id");
            signed.setIdAttribute("Id", true);
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            Reference reference = factory.newReference("#" + id,
                    factory.newDigestMethod(DigestMethod.SHA1, null),
                    List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                            factory.newTransform(CanonicalizationMethod.INCLUSIVE, (TransformParameterSpec) null)),
                    null, null);
            SignedInfo signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(SignatureMethod.RSA_SHA1, null), List.of(reference));
            KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
            KeyInfo keyInfo = keyInfoFactory.newKeyInfo(List.of(
                    keyInfoFactory.newX509Data(List.of(cert.certificate()))));
            DOMSignContext context = new DOMSignContext(cert.keyPair().getPrivate(), parent);
            context.setDefaultNamespacePrefix("");
            factory.newXMLSignature(signedInfo, keyInfo).sign(context);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao assinar a fixture", e);
        }
    }

    /** Valida a assinatura com o JDK, com a validação segura desligada (as fixtures são SHA-1). */
    public static boolean validates(byte[] xml, String signedElementName) {
        try {
            Document document = parse(xml);
            Element signed = (Element) document.getElementsByTagNameNS("*", signedElementName).item(0);
            signed.setIdAttribute("Id", true);
            var signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
            if (signatures.getLength() == 0) {
                return false;
            }
            javax.xml.crypto.dsig.dom.DOMValidateContext context = new javax.xml.crypto.dsig.dom.DOMValidateContext(
                    new javax.xml.crypto.KeySelector() {
                        @Override
                        public javax.xml.crypto.KeySelectorResult select(KeyInfo keyInfo,
                                javax.xml.crypto.KeySelector.Purpose purpose,
                                javax.xml.crypto.AlgorithmMethod method, javax.xml.crypto.XMLCryptoContext ctx)
                                throws javax.xml.crypto.KeySelectorException {
                            for (Object content : keyInfo.getContent()) {
                                if (content instanceof javax.xml.crypto.dsig.keyinfo.X509Data data) {
                                    for (Object item : data.getContent()) {
                                        if (item instanceof java.security.cert.X509Certificate cert) {
                                            return cert::getPublicKey;
                                        }
                                    }
                                }
                            }
                            throw new javax.xml.crypto.KeySelectorException("Sem certificado no KeyInfo");
                        }
                    }, signatures.item(0));
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
            XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            return signature.validate(context);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
