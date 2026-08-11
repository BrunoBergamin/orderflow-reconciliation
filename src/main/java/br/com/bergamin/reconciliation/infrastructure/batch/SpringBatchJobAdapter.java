package br.com.bergamin.reconciliation.infrastructure.batch;

import br.com.bergamin.reconciliation.application.port.out.ReconciliationJobPort;
import br.com.bergamin.reconciliation.application.port.out.ReconciliationRunRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Adaptador que traduz o caso de uso em uma execucao de job do Spring Batch. */
@Component
public class SpringBatchJobAdapter implements ReconciliationJobPort {

    private static final Logger log = LoggerFactory.getLogger(SpringBatchJobAdapter.class);

    private final JobLauncher jobLauncher;
    private final Job reconciliationJob;
    private final ReconciliationRunRepositoryPort runs;

    public SpringBatchJobAdapter(JobLauncher jobLauncher,
                                 Job reconciliationJob,
                                 ReconciliationRunRepositoryPort runs) {
        this.jobLauncher = jobLauncher;
        this.reconciliationJob = reconciliationJob;
        this.runs = runs;
    }

    @Override
    public void launch(UUID runId, String salesFilePath, String settlementFilePath) {
        var parameters = new JobParametersBuilder()
                // O runId torna cada execucao uma JobInstance distinta, que e o que o
                // Spring Batch exige para nao tratar duas conciliacoes como a mesma.
                .addString(ReconciliationJobConfig.PARAM_RUN_ID, runId.toString())
                .addString(ReconciliationJobConfig.PARAM_SALES_FILE, salesFilePath)
                .addString(ReconciliationJobConfig.PARAM_SETTLEMENT_FILE, settlementFilePath)
                .toJobParameters();

        try {
            jobLauncher.run(reconciliationJob, parameters);
        } catch (Exception e) {
            log.error("falha ao iniciar a conciliacao {}", runId, e);
            runs.markFailed(runId, e.getMessage());
            throw new IllegalStateException("nao foi possivel iniciar a conciliacao", e);
        }
    }
}
