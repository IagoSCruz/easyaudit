package br.com.easyaudit.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import br.com.easyaudit.audit.fixtures.AuditCaseFixtures;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Exportação CSV com o limite reduzido para 3 linhas. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "easyaudit.audit.export.max-rows=3")
class AuditExportIT extends AbstractAuditIT {

    static final String VIEWER = TwoTenants.bearerA("VIEWER");

    @Autowired
    MockMvc mvc;

    private AuditCaseFixtures.Builder aCase() {
        return AuditCaseFixtures.aCase(admin, TwoTenants.A.value());
    }

    private byte[] export(String query) throws Exception {
        MvcResult started = mvc.perform(get("/api/v1/audits/export" + query).header("Authorization", VIEWER))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        assertThat(done.getResponse().getContentType()).startsWith("text/csv");
        assertThat(done.getResponse().getHeader("Content-Disposition")).contains("attachment");
        return done.getResponse().getContentAsByteArray();
    }

    @Test
    void exportsTheFilteredCasesAsUtf8CsvWithBomAndCommaDecimals() throws Exception {
        aCase().status("DIVERGENT").classification("DIVERGENT").carrier("11222333000181", "Rápidos; Norte").uf("SP")
                .emission(LocalDate.of(2026, 10, 5)).charged("103.00").reference("100.00").divergence("3.00")
                .failedChecks("TOTAL_VS_TABLE", "WEIGHT").alerts("QUOTE_ABOVE")
                .accessKey("35261011222333000181570010000001231123456780").insert();
        aCase().status("APPROVED").emission(LocalDate.of(2026, 10, 6)).insert(); // outro estado

        byte[] csv = export("?status=DIVERGENT&sort=emissionDate");

        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        assertThat(java.util.Arrays.copyOfRange(csv, 0, 3)).isEqualTo(bom);
        String text = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("numero_cte;serie_cte;chave_cte;cnpj_transportadora;transportadora;data_emissao;"
                + "uf_destino;estado;valor_cobrado;valor_referencia;divergencia;valor_aprovado;verificacoes_com_falha;alertas\r\n"
                + "000000123;001;35261011222333000181570010000001231123456780;11222333000181;\"Rápidos; Norte\";05/10/2026;"
                + "SP;DIVERGENT;103,00;100,00;3,00;;TOTAL_VS_TABLE,WEIGHT;QUOTE_ABOVE\r\n");
    }

    @Test
    void theExportHonoursTheListFilters() throws Exception {
        aCase().uf("SP").insert();
        aCase().uf("RJ").insert();
        String text = new String(export("?destinationUf=RJ"), StandardCharsets.UTF_8);
        assertThat(text.lines().count()).isEqualTo(2); // cabeçalho + 1
    }

    @Test
    void aFilterAboveTheLimitIs422WithTheLimit() throws Exception {
        for (int i = 0; i < 4; i++) {
            aCase().insert();
        }
        mvc.perform(get("/api/v1/audits/export").header("Authorization", VIEWER))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("EXPORT_TOO_LARGE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("3 linhas")))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void theLimitCountsOnlyTheFilteredCases() throws Exception {
        for (int i = 0; i < 3; i++) {
            aCase().status("APPROVED").insert();
        }
        aCase().status("DIVERGENT").classification("DIVERGENT").insert();
        assertThat(new String(export("?status=APPROVED"), StandardCharsets.UTF_8).lines().count()).isEqualTo(4);
    }

    @Test
    void otherTenantsCasesAreNotExported() throws Exception {
        aCase().insert();
        AuditCaseFixtures.aCase(admin, TwoTenants.B.value()).insert();
        assertThat(new String(export(""), StandardCharsets.UTF_8).lines().count()).isEqualTo(2);
    }
}
