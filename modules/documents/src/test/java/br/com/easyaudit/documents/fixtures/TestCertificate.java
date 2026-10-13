package br.com.easyaudit.documents.fixtures;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Par de chaves RSA e certificado autoassinado gerados em memória para assinar as fixtures. */
public final class TestCertificate {

    private static TestCertificate shared;

    private final KeyPair keyPair;
    private final X509Certificate certificate;

    private TestCertificate(KeyPair keyPair, X509Certificate certificate) {
        this.keyPair = keyPair;
        this.certificate = certificate;
    }

    /** Certificado compartilhado entre os testes da JVM (a geração da chave RSA é lenta). */
    public static synchronized TestCertificate shared() {
        if (shared == null) {
            shared = generate("CN=EASYAUDIT TESTE:11222333000181");
        }
        return shared;
    }

    public static TestCertificate generate(String subject) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            X500Name name = new X500Name(subject);
            Date from = new Date(System.currentTimeMillis() - 86_400_000L);
            Date to = new Date(System.currentTimeMillis() + 365L * 86_400_000L);
            var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.nanoTime()), from, to, name,
                    pair.getPublic());
            var signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
            return new TestCertificate(pair, cert);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar o certificado de teste", e);
        }
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    public X509Certificate certificate() {
        return certificate;
    }
}
