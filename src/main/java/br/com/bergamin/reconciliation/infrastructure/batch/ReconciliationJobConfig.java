package br.com.bergamin.reconciliation.infrastructure.batch;

import br.com.bergamin.reconciliation.domain.model.Divergence;
import br.com.bergamin.reconciliation.domain.model.Money;
import br.com.bergamin.reconciliation.domain.model.PaymentMethod;
import br.com.bergamin.reconciliation.domain.model.SaleRecord;
import br.com.bergamin.reconciliation.domain.model.SettlementRecord;
import br.com.bergamin.reconciliation.domain.service.ReconciliationEngine;
import br.com.bergamin.reconciliation.infrastructure.batch.mapper.SaleFieldSetMapper;
import br.com.bergamin.reconciliation.infrastructure.batch.mapper.SettlementFieldSetMapper;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * O job de conciliacao, em quatro passos.
 *
 * <p>Importar os dois arquivos antes de comparar (em vez de comparar durante a leitura) e
 * o que permite tolerar arquivos em qualquer ordem, cruzar por indice e reprocessar a
 * comparacao sem reler o arquivo. O custo e uma passada extra pelo banco; o ganho e o job
 * ser reiniciavel do passo onde parou, que e a razao de existir do Spring Batch.</p>
 */
@Configuration
public class ReconciliationJobConfig {

    public static final String JOB_NAME = "reconciliationJob";
    public static final String PARAM_RUN_ID = "runId";
    public static final String PARAM_SALES_FILE = "salesFile";
    public static final String PARAM_SETTLEMENT_FILE = "settlementFile";

    // ------------------------------------------------------------------ job

    @Bean
    public Job reconciliationJob(JobRepository jobRepository,
                                 Step importSalesStep,
                                 Step importSettlementsStep,
                                 Step reconcileStep,
                                 Step finalizeStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(importSalesStep)
                .next(importSettlementsStep)
                .next(reconcileStep)
                .next(finalizeStep)
                .build();
    }

    // ------------------------------------------------------- 1. importa vendas

    @Bean
    public Step importSalesStep(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                FlatFileItemReader<SaleRecord> salesReader,
                                ItemWriter<SaleRecord> salesWriter,
                                @Qualifier("salesSkipListener") ImportErrorSkipListener skipListener,
                                @Value("${reconciliation.batch.chunk-size:500}") int chunkSize,
                                @Value("${reconciliation.batch.skip-limit:1000}") int skipLimit) {

        return new StepBuilder("importSalesStep", jobRepository)
                .<SaleRecord, SaleRecord>chunk(chunkSize, transactionManager)
                .reader(salesReader)
                .writer(salesWriter)
                // Tolerancia a falha por linha: um arquivo de 50 mil linhas nao pode ser
                // rejeitado inteiro por causa de tres linhas mal formatadas.
                .faultTolerant()
                .skipLimit(skipLimit)
                .skip(FlatFileParseException.class)
                .listener(skipListener)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<SaleRecord> salesReader(
            @Value("#{jobParameters['" + PARAM_SALES_FILE + "']}") String path) {

        return new FlatFileItemReaderBuilder<SaleRecord>()
                .name("salesReader")
                .resource(new FileSystemResource(path))
                .linesToSkip(1)
                .delimited().delimiter(";")
                .names("transactionId", "orderReference", "saleDate",
                        "grossAmount", "paymentMethod", "installments")
                .fieldSetMapper(new SaleFieldSetMapper())
                .build();
    }

    @Bean
    @StepScope
    public ItemWriter<SaleRecord> salesWriter(
            JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['" + PARAM_RUN_ID + "']}") String runId) {

        UUID run = UUID.fromString(runId);
        return items -> jdbcTemplate.batchUpdate("""
                        INSERT INTO sale_record
                            (id, run_id, transaction_id, order_reference, sale_date,
                             gross_amount, payment_method, installments)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                items.getItems().stream().map(sale -> new Object[]{
                        UUID.randomUUID(), run, sale.transactionId(), sale.orderReference(),
                        Date.valueOf(sale.saleDate()), sale.grossAmount().amount(),
                        sale.paymentMethod().name(), sale.installments()
                }).toList());
    }

    @Bean
    @StepScope
    public ImportErrorSkipListener salesSkipListener(
            JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['" + PARAM_RUN_ID + "']}") String runId) {
        return new ImportErrorSkipListener(jdbcTemplate, UUID.fromString(runId), "VENDAS");
    }

    // -------------------------------------------------- 2. importa repasses

    @Bean
    public Step importSettlementsStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      FlatFileItemReader<SettlementRecord> settlementsReader,
                                      ItemWriter<SettlementRecord> settlementsWriter,
                                      @Qualifier("settlementsSkipListener") ImportErrorSkipListener skipListener,
                                      @Value("${reconciliation.batch.chunk-size:500}") int chunkSize,
                                      @Value("${reconciliation.batch.skip-limit:1000}") int skipLimit) {

        return new StepBuilder("importSettlementsStep", jobRepository)
                .<SettlementRecord, SettlementRecord>chunk(chunkSize, transactionManager)
                .reader(settlementsReader)
                .writer(settlementsWriter)
                .faultTolerant()
                .skipLimit(skipLimit)
                .skip(FlatFileParseException.class)
                .listener(skipListener)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<SettlementRecord> settlementsReader(
            @Value("#{jobParameters['" + PARAM_SETTLEMENT_FILE + "']}") String path) {

        return new FlatFileItemReaderBuilder<SettlementRecord>()
                .name("settlementsReader")
                .resource(new FileSystemResource(path))
                .linesToSkip(1)
                .delimited().delimiter(";")
                .names("transactionId", "settlementDate", "grossAmount", "feeAmount", "netAmount")
                .fieldSetMapper(new SettlementFieldSetMapper())
                .build();
    }

    @Bean
    @StepScope
    public ItemWriter<SettlementRecord> settlementsWriter(
            JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['" + PARAM_RUN_ID + "']}") String runId) {

        UUID run = UUID.fromString(runId);
        return items -> jdbcTemplate.batchUpdate("""
                        INSERT INTO settlement_record
                            (id, run_id, transaction_id, settlement_date,
                             gross_amount, fee_amount, net_amount)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                items.getItems().stream().map(settlement -> new Object[]{
                        UUID.randomUUID(), run, settlement.transactionId(),
                        Date.valueOf(settlement.settlementDate()),
                        settlement.grossAmount().amount(), settlement.feeAmount().amount(),
                        settlement.netAmount().amount()
                }).toList());
    }

    @Bean
    @StepScope
    public ImportErrorSkipListener settlementsSkipListener(
            JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['" + PARAM_RUN_ID + "']}") String runId) {
        return new ImportErrorSkipListener(jdbcTemplate, UUID.fromString(runId), "REPASSE");
    }

    // ------------------------------------------------------- 3. concilia

    @Bean
    public Step reconcileStep(JobRepository jobRepository,
                              PlatformTransactionManager transactionManager,
                              JdbcCursorItemReader<SaleRecord> importedSalesReader,
                              ItemProcessor<SaleRecord, List<Divergence>> reconcileProcessor,
                              ItemWriter<List<Divergence>> divergenceWriter,
                              @Value("${reconciliation.batch.chunk-size:500}") int chunkSize) {

        return new StepBuilder("reconcileStep", jobRepository)
                .<SaleRecord, List<Divergence>>chunk(chunkSize, transactionManager)
                .reader(importedSalesReader)
                .processor(reconcileProcessor)
                .writer(divergenceWriter)
                .build();
    }

    /** Cursor em vez de paginacao: percorre a tabela uma vez so, sem reordenar a cada pagina. */
    @Bean
    @StepScope
    public JdbcCursorItemReader<SaleRecord> importedSalesReader(
            javax.sql.DataSource dataSource,
            @Value("#{jobParameters['" + PARAM_RUN_ID + "']}") String runId) {

        return new JdbcCursorItemReaderBuilder<SaleRecord>()
                .name("importedSalesReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT transaction_id, order_reference, sale_date,
                               gross_amount, payment_method, installments
                        FROM sale_record WHERE run_id = ? ORDER BY transaction_id
                        """)
                .preparedStatementSetter((ps) -> ps.setObject(1, UUID.fromString(runId)))
                .rowMapper((rs, rowNum) -> new SaleRecord(
                        rs.getString("transaction_id"),
                        rs.getString("order_reference"),
                        rs.getDate("sale_date").toLocalDate(),
                        Money.of(rs.getBigDecimal("gross_amount")),
                        PaymentMethod.valueOf(rs.getString("payment_method")),
                        rs.getInt("installments")))
                .build();
    }

    /**
     * Aplica o motor de dominio a cada venda.
     *
     * <p>Busca os repasses da transacao por indice ({@code run_id, transaction_id}), o que
     * mantem o custo constante por linha. Devolver {@code null} quando nao ha divergencia
     * faz o Batch filtrar o item. O {@code filterCount} do passo vira, de graca, a
     * contagem de vendas que fecharam certo.</p>
     */
    @Bean
    @StepScope
    public ItemProcessor<SaleRecord, List<Divergence>> reconcileProcessor(
            JdbcTemplate jdbcTemplate,
            ReconciliationEngine engine,
            @Value("#{jobParameters['" + PARAM_RUN_ID + "']}") String runId) {

        UUID run = UUID.fromString(runId);
        return sale -> {
            List<SettlementRecord> settlements = jdbcTemplate.query("""
                            SELECT transaction_id, settlement_date, gross_amount, fee_amount, net_amount
                            FROM settlement_record WHERE run_id = ? AND transaction_id = ?
                            """,
                    (rs, rowNum) -> new SettlementRecord(
                            rs.getString("transaction_id"),
                            rs.getDate("settlement_date").toLocalDate(),
                            Money.of(rs.getBigDecimal("gross_amount")),
                            Money.of(rs.getBigDecimal("fee_amount")),
                            Money.of(rs.getBigDecimal("net_amount"))),
                    run, sale.transactionId());

            List<Divergence> divergences = engine.compare(sale, settlements);
            return divergences.isEmpty() ? null : divergences;
        };
    }

    @Bean
    @StepScope
    public ItemWriter<List<Divergence>> divergenceWriter(
            JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['" + PARAM_RUN_ID + "']}") String runId) {

        UUID run = UUID.fromString(runId);
        return (Chunk<? extends List<Divergence>> chunk) -> {
            List<Object[]> rows = new ArrayList<>();
            chunk.getItems().forEach(list -> list.forEach(divergence -> rows.add(new Object[]{
                    UUID.randomUUID(), run, divergence.type().name(),
                    divergence.type().severity().name(), divergence.transactionId(),
                    divergence.orderReference(),
                    amountOrNull(divergence.expectedAmount()),
                    amountOrNull(divergence.foundAmount()),
                    amountOrNull(divergence.difference()),
                    divergence.details()
            })));

            if (!rows.isEmpty()) {
                jdbcTemplate.batchUpdate("""
                        INSERT INTO divergence
                            (id, run_id, type, severity, transaction_id, order_reference,
                             expected_amount, found_amount, difference, details)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rows);
            }
        };
    }

    // ------------------------------------------- 4. orfaos e fechamento

    @Bean
    public Step finalizeStep(JobRepository jobRepository,
                             PlatformTransactionManager transactionManager,
                             FinalizeReconciliationTasklet tasklet) {
        return new StepBuilder("finalizeStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    private static java.math.BigDecimal amountOrNull(Money money) {
        return money == null ? null : money.amount();
    }
}
