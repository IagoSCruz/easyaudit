package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.TenancyFixtures;
import br.com.easyaudit.tenancy.application.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest(classes = DocumentsTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DocumentUploadIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    TenantService tenants;
    @Autowired
    JdbcTemplate jdbc;

    JdbcTemplate admin;

    @BeforeEach
    void setUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate documents.raw_document, documents.pending_cancellation, documents.document cascade");
        TenancyFixtures.seedTwoTenants(admin);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
    }

    private static byte[] zip(Object... namesAndContents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) namesAndContents[i]));
                zip.write((byte[]) namesAndContents[i + 1]);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private MockMultipartHttpServletRequestBuilder upload(MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/documents");
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        return request;
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "application/octet-stream", bytes);
    }

    @Test
    void mixedZipListsEachResult() throws Exception {
        FiscalXml duplicate = CteFixture.builder().number(5).build();
        // o CT-e 5 já foi recebido antes
        mvc.perform(upload(file("antigo.xml", duplicate.bytes())).header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));

        byte[] archive = zip(
                "a.xml", CteFixture.builder().number(1).build().bytes(),
                "b.xml", CteFixture.builder().number(2).build().bytes(),
                "c.xml", CteFixture.builder().number(3).build().bytes(),
                "corrompido.xml", "<cteProc><CTe></cteProc>".getBytes(),
                "repetido.xml", duplicate.bytes());

        mvc.perform(upload(file("lote.zip", archive)).header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[?(@.status=='ACCEPTED')].filename").value(
                        org.hamcrest.Matchers.containsInAnyOrder("lote.zip/a.xml", "lote.zip/b.xml", "lote.zip/c.xml")))
                .andExpect(jsonPath("$.items[?(@.filename=='lote.zip/corrompido.xml')].status").value("REJECTED"))
                .andExpect(jsonPath("$.items[?(@.filename=='lote.zip/corrompido.xml')].reason").value("MALFORMED_XML"))
                .andExpect(jsonPath("$.items[?(@.filename=='lote.zip/repetido.xml')].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.items[?(@.filename=='lote.zip/repetido.xml')].documentId").isNotEmpty());
        assertThat(admin.queryForObject("select count(*) from documents.document", Integer.class)).isEqualTo(4);
    }

    @Test
    void oversizedXmlIsRejectedAndTheOthersContinue() throws Exception {
        byte[] huge = new byte[1024 * 1024 + 10];
        java.util.Arrays.fill(huge, (byte) ' ');
        mvc.perform(upload(file("grande.xml", huge), file("ok.xml", CteFixture.builder().number(7).build().bytes()))
                        .header("Authorization", TwoTenants.bearerA("TENANT_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reason").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.items[1].status").value("ACCEPTED"));
    }

    @Test
    void oversizedEntryInsideAZipIsRejectedAlone() throws Exception {
        byte[] huge = new byte[1024 * 1024 + 10];
        byte[] archive = zip("grande.xml", huge, "ok.xml", CteFixture.builder().number(8).build().bytes());
        mvc.perform(upload(file("z.zip", archive)).header("Authorization", TwoTenants.bearerA("FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.filename=='z.zip/grande.xml')].reason").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.items[?(@.filename=='z.zip/ok.xml')].status").value("ACCEPTED"));
    }

    @Test
    void viewerCannotUpload() throws Exception {
        mvc.perform(upload(file("a.xml", CteFixture.builder().build().bytes()))
                        .header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isForbidden());
        assertThat(admin.queryForObject("select count(*) from documents.document", Integer.class)).isZero();
    }

    @Test
    void zipBombIsRejectedWithoutExpandingEverything() throws Exception {
        // ~150 MB de zeros compactam para poucos KB: excede o orçamento de 100 MB descompactados
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 150; i++) {
                zip.putNextEntry(new ZipEntry("bomba-" + i + ".xml"));
                zip.write(zeros);
                zip.closeEntry();
            }
        }
        assertThat(out.size()).isLessThan(2 * 1024 * 1024);
        mvc.perform(upload(file("bomba.zip", out.toByteArray())).header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.items[0].detail").value(org.hamcrest.Matchers.containsString("zip bomb")));
    }

    @Test
    void zipWithMoreThan500EntriesIsRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < 501; i++) {
                zip.putNextEntry(new ZipEntry("e" + i + ".xml"));
                zip.write("x".getBytes());
                zip.closeEntry();
            }
        }
        mvc.perform(upload(file("muitos.zip", out.toByteArray())).header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.items[0].detail").value(org.hamcrest.Matchers.containsString("500")));
    }

    @Test
    void corruptZipIsRejected() throws Exception {
        byte[] bad = zip("a.xml", "<x/>".getBytes());
        bad[bad.length / 2] ^= (byte) 0xFF;
        byte[] truncated = java.util.Arrays.copyOf(bad, bad.length / 2);
        mvc.perform(upload(file("quebrado.zip", truncated)).header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("REJECTED"));
    }

    @Test
    void entryNamesCannotEscapeAnything() throws Exception {
        byte[] archive = zip("../../etc/passwd.xml", CteFixture.builder().number(9).build().bytes(), "__MACOSX/._a.xml", new byte[] {1});
        mvc.perform(upload(file("slip.zip", archive)).header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));
    }
}
