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
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O job completo, ponta a ponta, contra um cenario com um problema de cada tipo.
 *
 * <p>Os arquivos foram montados para que cada apontamento tenha um motivo claro, e o teste
 * confere tanto a quantidade quanto os valores, inclusive o total em risco, que e o
 * numero que o dono da loja olha.</p>
 */
@DisplayName("Job de conciliacao (integracao)")
class ReconciliationJobIT extends AbstractIntegrationTest {

    /**
     * O utilitario e montado a mao em vez de vir de {@code @SpringBatchTest}.
     *
     * <p>Aquela anotacao registra um listener que varre a classe de teste procurando um
     * metodo que devolva {@code JobExecution} e o executa antes do {@code @BeforeEach} --
     * o que faria o job rodar antes do cenario existir.</p>
     */
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

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    private Path pastaDeArquivos;
    private UUID runId;

    @BeforeEach
    void prepararCenario() throws IOException {
        limparBanco();
        runId = criarExecucao("vendas.csv", "repasse.csv");
        pastaDeArquivos = Files.createTempDirectory("conciliacao-teste");
    }

    @AfterEach
    void limparArquivos() throws IOException {
        try (var arquivos = Files.walk(pastaDeArquivos)) {
            arquivos.sorted(java.util.Comparator.reverseOrder()).forEach(caminho -> {
                try {
                    Files.deleteIfExists(caminho);
                } catch (IOException ignored) {
                    // Diretorio temporario: falha na limpeza nao invalida o teste.
                }
            });
        }
    }

    /**
     * Vendas da loja. Duas ultimas linhas sao propositalmente invalidas: uma sem
     * separadores, outra com data que nao existe.
     */
    private Path arquivoDeVendas() throws IOException {
        return escrever("vendas.csv", """
                transaction_id;order_reference;sale_date;gross_amount;payment_method;installments
                TX-001;PED-001;2026-08-01;1000.00;CREDITO;1
                TX-002;PED-002;2026-08-01;500.00;DEBITO;1
                TX-003;PED-003;2026-08-02;250.00;CREDITO;1
                TX-004;PED-004;2026-08-02;800.00;CREDITO;1
                TX-005;PED-005;2026-08-03;1200.00;CREDITO;1
                linha corrompida sem separador nenhum
                TX-006;PED-006;data-que-nao-existe;100.00;CREDITO;1
                """);
    }

    /**
     * Repasse do adquirente:
     * <ul>
     *   <li>TX-001 e TX-002 fecham certo</li>
     *   <li>TX-003 veio com valor menor que a venda</li>
     *   <li>TX-004 veio com taxa de 5% onde o contrato diz 3,19%</li>
     *   <li>TX-005 nao veio (venda sem repasse)</li>
     *   <li>TX-999 veio sem venda correspondente</li>
     * </ul>
     */
    private Path arquivoDeRepasse() throws IOException {
        return escrever("repasse.csv", """
                transaction_id;settlement_date;gross_amount;fee_amount;net_amount
                TX-001;2026-08-31;1000.00;31.90;968.10
                TX-002;2026-08-31;500.00;9.95;490.05
                TX-003;2026-08-31;200.00;6.38;193.62
                TX-004;2026-08-31;800.00;40.00;760.00
                TX-999;2026-08-31;300.00;9.57;290.43
                """);
    }

    @Test
    @DisplayName("importa, concilia e fecha o resumo com o total em risco")
    void executaConciliacaoCompleta() throws Exception {
        JobExecution execution = executarJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Cinco vendas validas: as duas linhas quebradas foram puladas, nao derrubaram o job.
        assertThat(contar("sale_record", runId)).isEqualTo(5);
        assertThat(contar("settlement_record", runId)).isEqualTo(5);
        assertThat(contar("import_error", runId)).isEqualTo(2);

        Map<String, Object> resumo = jdbcTemplate.queryForMap(
                "SELECT * FROM reconciliation_run WHERE id = ?", runId);

        assertThat(resumo.get("status")).isEqualTo("CONCLUIDA");
        assertThat(resumo.get("sales_read")).isEqualTo(5L);
        assertThat(resumo.get("divergences")).isEqualTo(4L);
        assertThat(resumo.get("critical_divergences")).isEqualTo(3L);
        assertThat(resumo.get("matched")).as("TX-001 e TX-002 fecharam certo").isEqualTo(2L);

        // 50,00 do valor divergente + 14,48 de taxa cobrada a mais + 1200,00 que nao entrou.
        assertThat((BigDecimal) resumo.get("amount_at_risk")).isEqualByComparingTo("1264.48");
    }

    @Test
    @DisplayName("cada divergencia aparece com o tipo e o valor certos")
    void apontaCadaTipoDeDivergencia() throws Exception {
        executarJob();

        assertThat(tiposEncontrados()).containsExactlyInAnyOrder(
                "VALOR_DIVERGENTE", "TAXA_ACIMA_DO_CONTRATADO",
                "VENDA_SEM_REPASSE", "REPASSE_SEM_VENDA");

        assertThat(diferencaDe("TX-003")).isEqualByComparingTo("-50.00");
        assertThat(diferencaDe("TX-005")).isEqualByComparingTo("-1200.00");
        // 800,00 a 3,19% dariam 25,52 de taxa; cobraram 40,00.
        assertThat(diferencaDe("TX-004")).isEqualByComparingTo("14.48");
        assertThat(diferencaDe("TX-999")).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("linha ruim e guardada com numero, conteudo e motivo")
    void registraLinhasIgnoradas() throws Exception {
        executarJob();

        List<Map<String, Object>> erros = jdbcTemplate.queryForList(
                "SELECT * FROM import_error WHERE run_id = ? ORDER BY line_number", runId);

        assertThat(erros).hasSize(2);
        assertThat(erros).allSatisfy(erro -> {
            assertThat(erro.get("source_file")).isEqualTo("VENDAS");
            assertThat(erro.get("raw_line")).isNotNull();
            assertThat(erro.get("message")).isNotNull();
        });
        assertThat(erros.get(1).get("raw_line").toString()).contains("data-que-nao-existe");
    }

    @Test
    @DisplayName("arquivos que fecham perfeitamente nao geram apontamento")
    void arquivoSemDivergencia() throws Exception {
        Path vendas = escrever("vendas-ok.csv", """
                transaction_id;order_reference;sale_date;gross_amount;payment_method;installments
                TX-100;PED-100;2026-08-01;1000.00;CREDITO;1
                """);
        Path repasse = escrever("repasse-ok.csv", """
                transaction_id;settlement_date;gross_amount;fee_amount;net_amount
                TX-100;2026-08-31;1000.00;31.90;968.10
                """);

        JobExecution execution = jobLauncherTestUtils.launchJob(parametros(vendas, repasse));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(contar("divergence", runId)).isZero();

        Map<String, Object> resumo = jdbcTemplate.queryForMap(
                "SELECT matched, divergences, amount_at_risk FROM reconciliation_run WHERE id = ?", runId);
        assertThat(resumo.get("matched")).isEqualTo(1L);
        assertThat(resumo.get("divergences")).isEqualTo(0L);
        assertThat((BigDecimal) resumo.get("amount_at_risk")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("aceita valor no formato brasileiro (1.234,56)")
    void aceitaFormatoBrasileiro() throws Exception {
        Path vendas = escrever("vendas-br.csv", """
                transaction_id;order_reference;sale_date;gross_amount;payment_method;installments
                TX-200;PED-200;2026-08-01;1.234,56;CREDITO;1
                """);
        Path repasse = escrever("repasse-br.csv", """
                transaction_id;settlement_date;gross_amount;fee_amount;net_amount
                TX-200;2026-08-31;1.234,56;39,38;1.195,18
                """);

        jobLauncherTestUtils.launchJob(parametros(vendas, repasse));

        assertThat(contar("import_error", runId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT gross_amount FROM sale_record WHERE run_id = ?", BigDecimal.class, runId))
                .isEqualByComparingTo("1234.56");
        assertThat(contar("divergence", runId)).isZero();
    }

    // ---------------------------------------------------------------- auxiliares

    private JobExecution executarJob() throws Exception {
        return jobLauncherTestUtils.launchJob(parametros(arquivoDeVendas(), arquivoDeRepasse()));
    }

    private JobParameters parametros(Path vendas, Path repasse) {
        return new JobParametersBuilder()
                .addString(ReconciliationJobConfig.PARAM_RUN_ID, runId.toString())
                .addString(ReconciliationJobConfig.PARAM_SALES_FILE, vendas.toAbsolutePath().toString())
                .addString(ReconciliationJobConfig.PARAM_SETTLEMENT_FILE, repasse.toAbsolutePath().toString())
                .toJobParameters();
    }

    private Path escrever(String nome, String conteudo) throws IOException {
        Path arquivo = pastaDeArquivos.resolve(nome);
        Files.writeString(arquivo, conteudo);
        return arquivo;
    }

    private List<String> tiposEncontrados() {
        return jdbcTemplate.queryForList(
                "SELECT type FROM divergence WHERE run_id = ?", String.class, runId);
    }

    private BigDecimal diferencaDe(String transactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT difference FROM divergence WHERE run_id = ? AND transaction_id = ?",
                BigDecimal.class, runId, transactionId);
    }
}
