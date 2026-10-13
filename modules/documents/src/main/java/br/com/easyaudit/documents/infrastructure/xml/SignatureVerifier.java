package br.com.easyaudit.documents.infrastructure.xml;

import java.io.ByteArrayInputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import br.com.easyaudit.documents.domain.DocumentType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Verifica a assinatura XMLDSig de CT-e, NF-e e eventos. Além da conferência criptográfica, exige que o documento
 * tenha exatamente uma assinatura e um único elemento assinado esperado (infCte, infNFe ou infEvento), no lugar
 * certo, e que a {@code Reference/@URI} aponte para o {@code Id} desse elemento. Isso barra *signature wrapping*,
 * em que o conteúdo assinado fica escondido em outro ponto do XML enquanto o lido pela aplicação é forjado.
 * A cadeia de certificação (ICP-Brasil) não é validada; só a integridade da assinatura.
 */
public final class SignatureVerifier {

    static {
        XmlDsigPolicy.install();
    }

    private SignatureVerifier() {
    }

    /**
     * @throws InvalidSignatureException assinatura ausente, adulterada ou que não cobre o elemento esperado
     * @throws MalformedXmlException     XML corrompido ou com DTD
     */
    public static void verify(byte[] xml, DocumentType type) {
        Document document = parse(xml);
        String signedName = type.isCancellation() ? "infEvento" : type == DocumentType.CTE ? "infCte" : "infNFe";
        Set<String> containers = switch (type) {
            case CTE -> Set.of("CTe");
            case NFE -> Set.of("NFe");
            case CTE_CANCELLATION -> Set.of("eventoCTe");
            case NFE_CANCELLATION -> Set.of("evento");
        };

        List<Element> signed = new ArrayList<>();
        int strangers = 0;
        NodeList candidates = document.getElementsByTagNameNS("*", signedName);
        for (int i = 0; i < candidates.getLength(); i++) {
            Element e = (Element) candidates.item(i);
            if (containers.contains(parentName(e))) {
                signed.add(e);
            } else if (!parentName(e).startsWith("retEvento")) {
                // o infEvento do retorno de registro é legítimo; qualquer outra cópia é suspeita
                strangers++;
            }
        }
        if (signed.size() != 1 || strangers > 0) {
            throw new InvalidSignatureException("O documento deve ter exatamente um elemento " + signedName
                    + " assinado, no lugar esperado.");
        }
        Element expected = signed.get(0);
        String id = expected.getAttribute("Id");
        if (id.isEmpty()) {
            throw new InvalidSignatureException("O elemento " + signedName + " não tem Id.");
        }

        NodeList signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (signatures.getLength() != 1) {
            throw new InvalidSignatureException("O documento deve ter exatamente uma assinatura (encontradas: "
                    + signatures.getLength() + ").");
        }
        Element signature = (Element) signatures.item(0);
        if (!containers.contains(parentName(signature))) {
            throw new InvalidSignatureException("A assinatura não está no elemento esperado.");
        }

        expected.setIdAttribute("Id", true);
        try {
            DOMValidateContext context = new DOMValidateContext(new X509KeySelector(), signature);
            XMLSignature xmlSignature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            List<?> references = xmlSignature.getSignedInfo().getReferences();
            if (references.size() != 1 || !("#" + id).equals(((Reference) references.get(0)).getURI())) {
                throw new InvalidSignatureException("A assinatura não referencia o elemento " + signedName + ".");
            }
            if (!xmlSignature.validate(context)) {
                throw new InvalidSignatureException("A assinatura digital não confere com o conteúdo.");
            }
        } catch (MarshalException | XMLSignatureException e) {
            throw new InvalidSignatureException("Assinatura inválida: " + e.getMessage(), e);
        }
    }

    private static String parentName(Node node) {
        Node parent = node.getParentNode();
        return parent == null ? "" : parent.getLocalName() == null ? "" : parent.getLocalName();
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        } catch (SAXException | java.io.IOException e) {
            throw new MalformedXmlException("XML malformado: " + e.getMessage(), e);
        }
    }

    /** Usa a chave pública do certificado X.509 do {@code KeyInfo}. */
    private static final class X509KeySelector extends KeySelector {
        @Override
        public KeySelectorResult select(KeyInfo keyInfo, Purpose purpose, AlgorithmMethod method,
                XMLCryptoContext context) throws KeySelectorException {
            if (keyInfo != null) {
                for (Object content : keyInfo.getContent()) {
                    if (content instanceof X509Data data) {
                        for (Object item : data.getContent()) {
                            if (item instanceof X509Certificate certificate) {
                                return certificate::getPublicKey;
                            }
                        }
                    }
                }
            }
            throw new KeySelectorException("Sem certificado X.509 no KeyInfo.");
        }
    }
}
