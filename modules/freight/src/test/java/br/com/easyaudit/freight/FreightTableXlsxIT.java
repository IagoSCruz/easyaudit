package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import com.jayway.jsonpath.JsonPath;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = FreightTestApplication.class,
        properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=false"})
@AutoConfigureMockMvc
class FreightTableXlsxIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;

    static final String ADMIN = TwoTenants.bearerA("TENANT_ADMIN");
    JdbcTemplate admin;
    String carrierId;

    @BeforeEach
    void setUp() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate freight.freight_table, freight.component_alias, freight.carrier cascade");
        carrierId = JsonPath.read(mvc.perform(post("/api/v1/carriers").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cnpj\":\"11222333000181\",\"name\":\"X\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private byte[] downloadTemplate() throws Exception {
        return mvc.perform(get("/api/v1/freight-tables/template").header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(byte[] xlsx) {
        return multipart("/api/v1/freight-tables/import").file(new MockMultipartFile("file", "tabela.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx)).param("carrierId", carrierId);
    }

    @Test
    void templateHasTheFourSheetsWithHeadersAndOneExampleRow() throws Exception {
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(downloadTemplate()))) {
            assertThat(wb.getSheets().map(s -> s.getName()).toList()).containsExactly("Geral", "Rotas", "Faixas", "AreasEspeciais");
            var rotas = wb.findSheet("Rotas").orElseThrow().read();
            assertThat(rotas.get(0).getCellText(0)).isEqualTo("Rota");
            assertThat(rotas.get(0).getCellText(1)).isEqualTo("CepDestinoInicio");
            assertThat(rotas.get(1).getCellText(1)).isEqualTo("01000000");
            assertThat(wb.findSheet("Faixas").orElseThrow().read()).hasSize(4); // cabeçalho + 3 faixas de exemplo
            assertThat(wb.findSheet("Geral").orElseThrow().read()).hasSize(2);
            assertThat(wb.findSheet("AreasEspeciais").orElseThrow().read()).hasSize(2);
        }
    }

    @Test
    void reimportingTheTemplateCreatesADraftWithNoErrors() throws Exception {
        mvc.perform(upload(downloadTemplate()).header("Authorization", ADMIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value("Tabela 2026"))
                .andExpect(jsonPath("$.includesIcms").value(false))
                .andExpect(jsonPath("$.content.cubingFactor").value(300))
                .andExpect(jsonPath("$.content.routes.length()").value(1))
                .andExpect(jsonPath("$.content.routes[0].destinationCepFrom").value("01000000"))
                .andExpect(jsonPath("$.content.routes[0].weightBands.length()").value(3))
                .andExpect(jsonPath("$.content.routes[0].tollPer100Kg").value(3.1))
                .andExpect(jsonPath("$.content.specialAreas[0].type").value("TDE"));
    }

    private static byte[] workbook(Consumer<Worksheet[]> fill) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Workbook wb = new Workbook(out, "teste", "1.0");
        Worksheet[] sheets = {wb.newWorksheet("Geral"), wb.newWorksheet("Rotas"), wb.newWorksheet("Faixas"),
                wb.newWorksheet("AreasEspeciais")};
        String[][] headers = {
                {"Nome", "InicioVigencia", "FimVigencia", "IncluiICMS", "FatorCubagem", "FreteMinimo", "TaxaDespacho"},
                {"Rota", "CepDestinoInicio", "CepDestinoFim", "CepOrigemInicio", "CepOrigemFim", "ExcedentePorKg",
                        "AdValoremPct", "AdValoremMinimo", "GrisPct", "GrisMinimo", "PedagioPor100Kg"},
                {"Rota", "DeKg", "AteKg", "Valor"},
                {"Tipo", "CepInicio", "CepFim", "PercentualSobreFretePeso", "Minimo"}};
        for (int s = 0; s < 4; s++) {
            for (int c = 0; c < headers[s].length; c++) {
                sheets[s].value(0, c, headers[s][c]);
            }
        }
        fill.accept(sheets);
        wb.finish();
        return out.toByteArray();
    }

    @Test
    void invalidCepOnRow12ColumnBIsReportedWithSheetRowAndColumn() throws Exception {
        byte[] xlsx = workbook(sheets -> {
            sheets[0].value(1, 0, "Tabela com erro");
            sheets[0].value(1, 1, "2026-01-01");
            sheets[0].value(1, 3, "NAO");
            sheets[0].value(1, 4, 300);
            // 11 rotas válidas (linhas 2 a 12) e a de linha 12 com CEP inválido na coluna B
            for (int i = 0; i < 11; i++) {
                int row = i + 1;
                int base = 1000000 * (i + 1);
                String name = "Rota " + i;
                sheets[1].value(row, 0, name);
                sheets[1].value(row, 1, i == 10 ? "ABC" : String.format("%08d", base));
                sheets[1].value(row, 2, String.format("%08d", base + 999999));
                sheets[2].value(row, 0, name);
                sheets[2].value(row, 1, 0);
                sheets[2].value(row, 2, 30);
                sheets[2].value(row, 3, 20);
            }
        });

        mvc.perform(upload(xlsx).header("Authorization", ADMIN))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("Rotas!B12"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("Aba Rotas"),
                        org.hamcrest.Matchers.containsString("linha 12"),
                        org.hamcrest.Matchers.containsString("coluna B"),
                        org.hamcrest.Matchers.containsString("CEP inválido"))));
        assertThat(admin.queryForObject("select count(*) from freight.freight_table", Integer.class)).isZero();
    }

    @Test
    void weightBandGapPointsToTheBandRow() throws Exception {
        byte[] xlsx = workbook(sheets -> {
            sheets[0].value(1, 0, "T");
            sheets[0].value(1, 1, "2026-01-01");
            sheets[0].value(1, 3, "SIM");
            sheets[0].value(1, 4, 300);
            sheets[1].value(1, 0, "R1");
            sheets[1].value(1, 1, "01000000");
            sheets[1].value(1, 2, "01999999");
            double[][] bands = {{0, 5, 20}, {6, 10, 28}};
            for (int i = 0; i < bands.length; i++) {
                sheets[2].value(i + 1, 0, "R1");
                sheets[2].value(i + 1, 1, bands[i][0]);
                sheets[2].value(i + 1, 2, bands[i][1]);
                sheets[2].value(i + 1, 3, bands[i][2]);
            }
        });
        mvc.perform(upload(xlsx).header("Authorization", ADMIN))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("Faixas!B3"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("sem continuidade")));
    }

    @Test
    void unreadableFilesAndMissingSheetsAreReported() throws Exception {
        mvc.perform(upload("isto não é uma planilha".getBytes()).header("Authorization", ADMIN))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_SPREADSHEET"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Workbook wb = new Workbook(out, "t", "1.0");
        wb.newWorksheet("Outra").value(0, 0, "x");
        wb.finish();
        mvc.perform(upload(out.toByteArray()).header("Authorization", ADMIN))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='Geral')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='Rotas')]").exists());
    }

    @Test
    void importRequiresTenantAdminAndAnExistingCarrier() throws Exception {
        mvc.perform(upload(downloadTemplate()).header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/freight-tables/import")
                        .file(new MockMultipartFile("file", "t.xlsx", "application/octet-stream", downloadTemplate()))
                        .param("carrierId", java.util.UUID.randomUUID().toString()).header("Authorization", ADMIN))
                .andExpect(status().isNotFound());
        assertThat(List.of(1)).isNotEmpty();
    }
}
