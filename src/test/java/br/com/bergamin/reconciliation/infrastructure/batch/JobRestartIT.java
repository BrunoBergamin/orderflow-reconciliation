package br.com.bergamin.reconciliation.infrastructure.batch;

import br.com.bergamin.reconciliation.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reinicio do ponto onde parou -- a caracteristica que justifica usar Spring Batch em vez
 * de um laco lendo arquivo.
 *
 * <p>O cenario e real: o relatorio de vendas chegou, o arquivo do adquirente atrasou. A
 * primeira execucao importa as vendas e falha ao procurar o repasse. Quando o arquivo
 * chega, a mesma execucao e relancada e retoma do passo que falhou -- sem reimportar as
 * vendas, sem duplicar linha, sem ninguem precisar limpar tabela na mao.</p>
 */
@DisplayName("Reinicio do job (integracao)")
class JobRestartIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class BatchTestConfig {

        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(Job reconciliationJob,
                                                  JobLauncher reconciliationJobLauncher,
                                                  JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(reconciliationJob);
            utils.setJobLauncher(reconciliationJobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }
    }

    private static final String VENDAS = """
            transaction_id;order_reference;sale_date;gross_amount;payment_method;installments
            TX-R1;PED-R1;2026-08-01;1000.00;CREDITO;1
            TX-R2;PED-R2;2026-08-01;500.00;DEBITO;1
            TX-R3;PED-R3;2026-08-02;250.00;CREDITO;1
            """;

    private static final String REPASSE = """
            transaction_id;settlement_date;gross_amount;fee_amount;net_amount
            TX-R1;2026-08-31;1000.00;31.90;968.10
            TX-R2;2026-08-31;500.00;9.95;490.05
            TX-R3;2026-08-31;250.00;7.98;242.02
            """;

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    private Path pasta;
    private UUID runId;

    @BeforeEach
    void prepararCenario() throws IOException {
        limparBanco();
        runId = criarExecucao("vendas.csv", "repasse.csv");
        pasta = Files.createTempDirectory("conciliacao-reinicio");
    }

    @AfterEach
    void limparArquivos() throws IOException {
        try (var arquivos = Files.walk(pasta)) {
            arquivos.sorted(Comparator.reverseOrder()).forEach(caminho -> {
                try {
                    Files.deleteIfExists(caminho);
                } catch (IOException ignored) {
                    // Diretorio temporario.
                }
            });
        }
    }

    @Test
    @DisplayName("o arquivo do adquirente atrasou: relanca e retoma sem reimportar as vendas")
    void retomaDoPassoQueFalhou() throws Exception {
        Path vendas = escrever("vendas.csv", VENDAS);
        Path repasse = pasta.resolve("repasse.csv");
        JobParameters parametros = parametros(vendas, repasse);

        // Primeira tentativa: o arquivo de repasse ainda nao existe.
        JobExecution primeira = jobLauncherTestUtils.launchJob(parametros);

        assertThat(primeira.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(statusDoPasso(primeira, "importSalesStep")).isEqualTo(BatchStatus.COMPLETED);
        assertThat(statusDoPasso(primeira, "importSettlementsStep")).isEqualTo(BatchStatus.FAILED);
        assertThat(contar("sale_record", runId)).isEqualTo(3);
        assertThat(contar("settlement_record", runId)).isZero();

        // O arquivo chega. Mesmos parametros: o Spring Batch reconhece a mesma execucao.
        Files.writeString(repasse, REPASSE);
        JobExecution segunda = jobLauncherTestUtils.launchJob(parametros);

        assertThat(segunda.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // O passo que ja tinha terminado nao roda de novo: as vendas continuam sendo 3, e
        // nao 6. Sem isso, reprocessar exigiria limpar tabela na mao antes.
        assertThat(contar("sale_record", runId)).isEqualTo(3);
        assertThat(contar("settlement_record", runId)).isEqualTo(3);
        assertThat(contar("divergence", runId)).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reconciliation_run WHERE id = ?", String.class, runId))
                .isEqualTo("CONCLUIDA");
    }

    @Test
    @DisplayName("o passo ja concluido aparece como ignorado no relancamento")
    void naoReexecutaPassoConcluido() throws Exception {
        Path vendas = escrever("vendas.csv", VENDAS);
        Path repasse = pasta.resolve("repasse.csv");
        JobParameters parametros = parametros(vendas, repasse);

        jobLauncherTestUtils.launchJob(parametros);
        Files.writeString(repasse, REPASSE);
        JobExecution segunda = jobLauncherTestUtils.launchJob(parametros);

        // O historico do Batch registra que a segunda execucao nem tentou o primeiro passo.
        assertThat(segunda.getStepExecutions())
                .extracting(StepExecution::getStepName)
                .doesNotContain("importSalesStep")
                .contains("importSettlementsStep", "reconcileStep", "finalizeStep");
    }

    // ---------------------------------------------------------------- auxiliares

    private BatchStatus statusDoPasso(JobExecution execution, String nome) {
        return execution.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals(nome))
                .map(StepExecution::getStatus)
                .findFirst()
                .orElseThrow(() -> new AssertionError("passo nao executado: " + nome));
    }

    private JobParameters parametros(Path vendas, Path repasse) {
        return new JobParametersBuilder()
                .addString(ReconciliationJobConfig.PARAM_RUN_ID, runId.toString())
                .addString(ReconciliationJobConfig.PARAM_SALES_FILE, vendas.toAbsolutePath().toString())
                .addString(ReconciliationJobConfig.PARAM_SETTLEMENT_FILE, repasse.toAbsolutePath().toString())
                .toJobParameters();
    }

    private Path escrever(String nome, String conteudo) throws IOException {
        Path arquivo = pasta.resolve(nome);
        Files.writeString(arquivo, conteudo);
        return arquivo;
    }
}
