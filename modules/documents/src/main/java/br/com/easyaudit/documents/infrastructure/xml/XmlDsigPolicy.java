package br.com.easyaudit.documents.infrastructure.xml;

import java.security.Security;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CT-e e NF-e são assinados com RSA-SHA1, que a validação segura de XMLDSig do JDK recusa por padrão. Este ajuste
 * remove da {@code jdk.xml.dsig.secureValidationPolicy} só as duas restrições de SHA-1 (digest e assinatura RSA) e
 * mantém todas as demais (MD5, XSLT, limites de transforms e referências, tamanhos mínimos de chave, IDs duplicados).
 * Nunca desliga a validação segura inteira.
 *
 * <p>A propriedade é lida pelo JDK na primeira vez que o XMLDSig é usado, então {@link #install()} precisa rodar
 * antes disso; os pontos de entrada deste módulo chamam {@code install()} em inicializadores estáticos.
 */
public final class XmlDsigPolicy {

    static final String PROPERTY = "jdk.xml.dsig.secureValidationPolicy";
    private static final List<String> SHA1_RESTRICTIONS = List.of(
            "disallowAlg http://www.w3.org/2000/09/xmldsig#sha1",
            "disallowAlg http://www.w3.org/2000/09/xmldsig#rsa-sha1");

    private static boolean installed;

    private XmlDsigPolicy() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        String current = Security.getProperty(PROPERTY);
        if (current != null) {
            Security.setProperty(PROPERTY, relaxSha1(current));
        }
        installed = true;
    }

    /** @return a política sem as duas linhas de SHA-1 (as demais permanecem exatamente como estão) */
    static String relaxSha1(String policy) {
        return Arrays.stream(policy.split(","))
                .filter(rule -> !SHA1_RESTRICTIONS.contains(rule.trim()))
                .collect(Collectors.joining(","));
    }
}
