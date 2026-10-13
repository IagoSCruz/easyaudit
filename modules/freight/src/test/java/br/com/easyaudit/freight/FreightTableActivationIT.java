package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = FreightTestApplication.class,
        properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=false"})
@AutoConfigureMockMvc
@RecordApplicationEvents
class FreightTableActivationIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper mapper;
    @Autowired
    ApplicationEvents applicationEvents;

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

    private String draft(LocalDate validFrom) throws Exception {
        FreightTableContent content = FreightTableValidatorTest.tableT();
        content.setValidFrom(validFrom);
        String body = "{\"carrierId\":\"" + carrierId + "\",\"content\":" + mapper.writeValueAsString(content) + "}";
        return JsonPath.read(mvc.perform(post("/api/v1/freight-tables").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void activate(String id) throws Exception {
        mvc.perform(post("/api/v1/freight-tables/" + id + "/activate").header("Authorization", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private String validTo(String id) {
        return admin.queryForObject("select valid_to::text from freight.freight_table where id = ?::uuid", String.class, id);
    }

    private List<EventEnvelope> activatedEvents() {
        return applicationEvents.stream(EventEnvelope.class)
                .filter(e -> "freight-table.activated".equals(e.getEventType().getValue())).toList();
    }

    @Test
    void newVersionClosesThePreviousOneTheDayBefore() throws Exception {
        String v1 = draft(LocalDate.of(2026, 1, 1));
        activate(v1);
        assertThat(validTo(v1)).isNull();

        String v2 = draft(LocalDate.of(2026, 11, 1));
        mvc.perform(post("/api/v1/freight-tables/" + v2 + "/activate").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.validFrom").value("2026-11-01"))
                .andExpect(jsonPath("$.activatedAt").isNotEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        assertThat(validTo(v1)).isEqualTo("2026-10-31");
        assertThat(validTo(v2)).isNull();
        assertThat(admin.queryForObject("select status from freight.freight_table where id = ?::uuid", String.class, v1))
                .isEqualTo("ACTIVE"); // a anterior continua valendo no período dela
    }

    @Test
    void activationPublishesTheEventWithCarrierAndPeriod() throws Exception {
        String v1 = draft(LocalDate.of(2026, 1, 1));
        activate(v1);
        assertThat(activatedEvents()).hasSize(1);
        var data = activatedEvents().get(0).getData();
        assertThat(data).containsEntry("carrierId", carrierId).containsEntry("tableVersionId", v1)
                .containsEntry("validFrom", "2026-01-01");
        assertThat(data).doesNotContainKey("validTo");
    }

    @Test
    void aRetroactiveActivationConflictsAndChangesNothing() throws Exception {
        String v1 = draft(LocalDate.of(2026, 6, 1));
        activate(v1);
        String v2 = draft(LocalDate.of(2026, 5, 1));
        long eventsBefore = activatedEvents().size();

        mvc.perform(post("/api/v1/freight-tables/" + v2 + "/activate").header("Authorization", ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVATION_NOT_AFTER_LATEST_VERSION"));

        assertThat(admin.queryForObject("select status from freight.freight_table where id = ?::uuid", String.class, v2))
                .isEqualTo("DRAFT");
        assertThat(validTo(v1)).isNull();
        assertThat(activatedEvents()).hasSize((int) eventsBefore);
        // o mesmo início também não vale (precisa ser posterior)
        String v3 = draft(LocalDate.of(2026, 6, 1));
        mvc.perform(post("/api/v1/freight-tables/" + v3 + "/activate").header("Authorization", ADMIN))
                .andExpect(status().isConflict());
    }

    @Test
    void anAlreadyActiveOrArchivedTableCannotBeActivatedAgain() throws Exception {
        String v1 = draft(LocalDate.of(2026, 1, 1));
        activate(v1);
        mvc.perform(post("/api/v1/freight-tables/" + v1 + "/activate").header("Authorization", ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_NOT_DRAFT"));
    }

    @Test
    void aPreviousVersionWithAnEarlierEndKeepsItsEnd() throws Exception {
        FreightTableContent content = FreightTableValidatorTest.tableT();
        content.setValidFrom(LocalDate.of(2026, 1, 1));
        content.setValidTo(LocalDate.of(2026, 3, 31));
        String body = "{\"carrierId\":\"" + carrierId + "\",\"content\":" + mapper.writeValueAsString(content) + "}";
        String v1 = JsonPath.read(mvc.perform(post("/api/v1/freight-tables").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString(), "$.id");
        activate(v1);
        String v2 = draft(LocalDate.of(2026, 11, 1));
        activate(v2);
        assertThat(validTo(v1)).isEqualTo("2026-03-31");
    }

    @Test
    void concurrentActivationsAreSerializedAndExactlyOneWins() throws Exception {
        String v1 = draft(LocalDate.of(2026, 1, 1));
        activate(v1);
        String a = draft(LocalDate.of(2026, 11, 1));
        String b = draft(LocalDate.of(2026, 11, 1));

        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> tasks = List.of(() -> statusOfActivation(a), () -> statusOfActivation(b));
            List<Integer> results = pool.invokeAll(tasks).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.toList());
            assertThat(results).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(admin.queryForObject("select count(*) from freight.freight_table where status = 'ACTIVE' and valid_from = '2026-11-01'",
                Integer.class)).isEqualTo(1);
        assertThat(validTo(v1)).isEqualTo("2026-10-31");
    }

    private int statusOfActivation(String id) throws Exception {
        return mvc.perform(post("/api/v1/freight-tables/" + id + "/activate").header("Authorization", ADMIN))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void onlyTenantAdminsActivate() throws Exception {
        String v1 = draft(LocalDate.of(2026, 1, 1));
        mvc.perform(post("/api/v1/freight-tables/" + v1 + "/activate").header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isForbidden());
    }
}
