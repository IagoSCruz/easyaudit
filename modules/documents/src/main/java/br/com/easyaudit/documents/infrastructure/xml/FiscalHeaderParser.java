package br.com.easyaudit.documents.infrastructure.xml;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import br.com.easyaudit.documents.domain.DocumentType;
import br.com.easyaudit.documents.domain.FiscalHeader;

/**
 * Parser StAX seguro do cabeçalho de CT-e, NF-e e eventos de cancelamento. Não aceita DTD nem entidades externas
 * (XXE) e não monta árvore: lê o documento em fluxo e guarda só os campos de cabeçalho.
 */
public final class FiscalHeaderParser {

    private static final Set<String> CTE_ROOTS = Set.of("cteProc", "CTe");
    private static final Set<String> NFE_ROOTS = Set.of("nfeProc", "NFe");
    private static final XMLInputFactory FACTORY = secureFactory();

    private FiscalHeaderParser() {
    }

    private static XMLInputFactory secureFactory() {
        // a implementação padrão do JDK, não a que o classpath (Woodstox, do Jackson XML) resolver
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    /**
     * @throws MalformedXmlException       XML corrompido ou com DTD/entidades
     * @throws UnsupportedDocumentException tipo ou versão não suportados
     */
    public static FiscalHeader parse(byte[] xml) {
        Collected c = new Collected();
        try {
            XMLStreamReader reader = FACTORY.createXMLStreamReader(new ByteArrayInputStream(xml));
            Deque<String> path = new ArrayDeque<>();
            StringBuilder text = new StringBuilder();
            try {
                while (reader.hasNext()) {
                    switch (reader.next()) {
                        case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                                throw new MalformedXmlException("DTD e entidades não são permitidos.");
                        case XMLStreamConstants.START_ELEMENT -> {
                            String name = reader.getLocalName();
                            if (path.isEmpty()) {
                                c.root = name;
                                c.rootVersion = reader.getAttributeValue(null, "versao");
                            }
                            path.push(name);
                            text.setLength(0);
                            c.onStart(path, reader);
                        }
                        case XMLStreamConstants.CHARACTERS -> text.append(reader.getText());
                        case XMLStreamConstants.END_ELEMENT -> {
                            c.onEnd(path, text.toString().trim());
                            path.pop();
                            text.setLength(0);
                        }
                        default -> { }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new MalformedXmlException("XML malformado: " + e.getMessage(), e);
        }
        return c.toHeader();
    }

    /** Estado da leitura: guarda os campos de interesse conforme o caminho do elemento. */
    private static final class Collected {
        String root;
        String rootVersion;
        String infVersion;
        String idAttribute;
        String model;
        String environment;
        String protocolStatus;
        String emitCnpj;
        String remCnpj;
        String expedCnpj;
        String recebCnpj;
        String destCnpj;
        String toma4Cnpj;
        String tomaCode;
        String toma4Cpf;
        String eventKey;
        String eventAuthor;
        String eventType;
        String eventSequence;
        String registrationStatus;
        String registrationEnvironment;

        private static String parent(Deque<String> path) {
            var it = path.iterator();
            it.next();
            return it.hasNext() ? it.next() : "";
        }

        void onStart(Deque<String> path, XMLStreamReader reader) {
            String name = path.peek();
            if (("infCte".equals(name) || "infNFe".equals(name)) && idAttribute == null) {
                idAttribute = reader.getAttributeValue(null, "Id");
                infVersion = reader.getAttributeValue(null, "versao");
            }
            if ("infEvento".equals(name) && isEventNode(parent(path)) && idAttribute == null) {
                idAttribute = reader.getAttributeValue(null, "Id");
            }
        }

        void onEnd(Deque<String> path, String value) {
            String name = path.peek();
            String parent = parent(path);
            if (value.isEmpty()) {
                return;
            }
            switch (parent) {
                case "ide" -> {
                    if ("mod".equals(name)) model = value;
                    if ("tpAmb".equals(name)) environment = value;
                }
                case "emit" -> { if ("CNPJ".equals(name)) emitCnpj = value; }
                case "rem" -> { if ("CNPJ".equals(name)) remCnpj = value; }
                case "exped" -> { if ("CNPJ".equals(name)) expedCnpj = value; }
                case "receb" -> { if ("CNPJ".equals(name)) recebCnpj = value; }
                case "dest" -> { if ("CNPJ".equals(name)) destCnpj = value; }
                case "toma3" -> { if ("toma".equals(name)) tomaCode = value; }
                case "toma4" -> {
                    if ("CNPJ".equals(name)) toma4Cnpj = value;
                    if ("CPF".equals(name)) toma4Cpf = value;
                    if ("toma".equals(name)) tomaCode = value;
                }
                case "infProt" -> {
                    if ("cStat".equals(name)) protocolStatus = value;
                    if ("tpAmb".equals(name) && environment == null) environment = value;
                }
                case "infEvento" -> collectEvent(path, name, value);
                default -> { }
            }
        }

        private void collectEvent(Deque<String> path, String name, String value) {
            var it = path.iterator();
            it.next();
            it.next();
            String grand = it.hasNext() ? it.next() : "";
            if (isEventNode(grand)) {
                switch (name) {
                    case "tpAmb" -> environment = value;
                    case "CNPJ" -> eventAuthor = value;
                    case "chCTe", "chNFe" -> eventKey = value;
                    case "tpEvento" -> eventType = value;
                    case "nSeqEvento" -> eventSequence = value;
                    default -> { }
                }
            } else if (grand.startsWith("retEvento")) {
                switch (name) {
                    case "cStat" -> registrationStatus = value;
                    case "tpAmb" -> registrationEnvironment = value;
                    default -> { }
                }
            }
        }

        private static boolean isEventNode(String name) {
            return "eventoCTe".equals(name) || "evento".equals(name);
        }

        FiscalHeader toHeader() {
            if (root == null) {
                throw new MalformedXmlException("Documento vazio.");
            }
            return switch (root) {
                case "procEventoCTe" -> event(DocumentType.CTE_CANCELLATION, "4.00");
                case "procEventoNFe" -> event(DocumentType.NFE_CANCELLATION, "1.00");
                default -> {
                    if (CTE_ROOTS.contains(root)) {
                        yield cte();
                    }
                    if (NFE_ROOTS.contains(root)) {
                        yield nfe();
                    }
                    throw new UnsupportedDocumentException("Tipo de documento não suportado: " + root);
                }
            };
        }

        private FiscalHeader cte() {
            String version = infVersion != null ? infVersion : rootVersion;
            if (idAttribute == null || !idAttribute.startsWith("CTe")) {
                throw new MalformedXmlException("CT-e sem o elemento infCte identificado.");
            }
            if (!"4.00".equals(version)) {
                throw new UnsupportedDocumentException("CT-e na versão " + version + " não é suportado.");
            }
            if (!"57".equals(model)) {
                throw new UnsupportedDocumentException("Modelo de CT-e não suportado: " + model);
            }
            String taker = switch (tomaCode == null ? "" : tomaCode) {
                case "0" -> remCnpj;
                case "1" -> expedCnpj;
                case "2" -> recebCnpj;
                case "3" -> destCnpj;
                case "4" -> toma4Cnpj;
                default -> throw new MalformedXmlException("CT-e sem tomador do serviço.");
            };
            return new FiscalHeader(DocumentType.CTE, version, requireEnvironment(environment),
                    idAttribute.substring(3), protocolStatus, emitCnpj, taker, null, null, null);
        }

        private FiscalHeader nfe() {
            String version = infVersion != null ? infVersion : rootVersion;
            if (idAttribute == null || !idAttribute.startsWith("NFe")) {
                throw new MalformedXmlException("NF-e sem o elemento infNFe identificado.");
            }
            if (!"4.00".equals(version)) {
                throw new UnsupportedDocumentException("NF-e na versão " + version + " não é suportada.");
            }
            if (!"55".equals(model)) {
                throw new UnsupportedDocumentException("Modelo de NF-e não suportado: " + model);
            }
            return new FiscalHeader(DocumentType.NFE, version, requireEnvironment(environment),
                    idAttribute.substring(3), protocolStatus, emitCnpj, null, destCnpj, null, null);
        }

        private FiscalHeader event(DocumentType type, String expectedVersion) {
            if (!expectedVersion.equals(rootVersion)) {
                throw new UnsupportedDocumentException("Evento na versão " + rootVersion + " não é suportado.");
            }
            if (!FiscalHeader.CANCELLATION_EVENT.equals(eventType)) {
                throw new UnsupportedDocumentException("Evento " + eventType + " não é cancelamento.");
            }
            if (eventKey == null || eventSequence == null) {
                throw new MalformedXmlException("Evento sem chave ou sequência.");
            }
            int seq;
            try {
                seq = Integer.parseInt(eventSequence);
            } catch (NumberFormatException e) {
                throw new MalformedXmlException("Sequência de evento inválida: " + eventSequence);
            }
            return new FiscalHeader(type, rootVersion, requireEnvironment(environment), eventKey, registrationStatus,
                    eventAuthor, null, null, eventType, seq);
        }

        private static int requireEnvironment(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new MalformedXmlException("tpAmb ausente ou inválido.");
            }
        }
    }
}
