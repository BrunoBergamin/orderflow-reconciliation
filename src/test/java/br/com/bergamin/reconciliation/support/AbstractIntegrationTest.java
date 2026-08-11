package br.com.bergamin.reconciliation.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * PostgreSQL real via Testcontainers.
 *
 * <p>Aqui nao havia escolha: o job usa {@code gen_random_uuid()}, indice parcial e as
 * tabelas de controle do proprio Spring Batch. Em banco em memoria nada disso se comporta
 * igual, e o teste passaria dizendo pouco.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("reconciliation")
                    .withUsername("reconciliation")
                    .withPassword("reconciliation");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** Limpa os dados de negocio e o historico de execucoes do Batch entre os testes. */
    protected void limparBanco() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE divergence, import_error, sale_record, settlement_record,
                               reconciliation_run CASCADE
                """);
        jdbcTemplate.execute("""
                TRUNCATE TABLE batch_step_execution_context, batch_step_execution,
                               batch_job_execution_context, batch_job_execution_params,
                               batch_job_execution, batch_job_instance RESTART IDENTITY CASCADE
                """);
    }

    protected UUID criarExecucao(String salesFile, String settlementFile) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO reconciliation_run
                            (id, sales_file, settlement_file, status, started_at)
                        VALUES (?, ?, ?, 'EM_ANDAMENTO', ?)
                        """,
                runId, salesFile, settlementFile, Timestamp.from(Instant.now()));
        return runId;
    }

    protected long contar(String tabela, UUID runId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tabela + " WHERE run_id = ?", Long.class, runId);
        return total == null ? 0 : total;
    }
}
