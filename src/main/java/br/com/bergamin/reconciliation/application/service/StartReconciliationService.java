package br.com.bergamin.reconciliation.application.service;

import br.com.bergamin.reconciliation.application.port.in.StartReconciliationUseCase;
import br.com.bergamin.reconciliation.application.port.out.ReconciliationJobPort;
import br.com.bergamin.reconciliation.application.port.out.ReconciliationRunRepositoryPort;
import br.com.bergamin.reconciliation.domain.model.ReconciliationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Registra a execucao antes de disparar o processamento.
 *
 * <p>A ordem importa: a linha em {@code reconciliation_run} nasce com status
 * {@code EM_ANDAMENTO} <b>antes</b> de o job comecar. Se o processo cair no meio, fica o
 * rastro de uma execucao que ficou pendurada -- melhor do que um arquivo processado pela
 * metade sem nenhum registro de que a tentativa existiu.</p>
 */
@Service
public class StartReconciliationService implements StartReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(StartReconciliationService.class);

    private final ReconciliationRunRepositoryPort runs;
    private final ReconciliationJobPort job;
    private final Clock clock;

    public StartReconciliationService(ReconciliationRunRepositoryPort runs,
                                      ReconciliationJobPort job,
                                      Clock clock) {
        this.runs = runs;
        this.job = job;
        this.clock = clock;
    }

    @Override
    public UUID start(Command command) {
        UUID runId = UUID.randomUUID();

        runs.save(ReconciliationRun.starting(
                runId,
                command.salesFileName(),
                command.settlementFileName(),
                command.referenceDate(),
                clock.instant()));

        try {
            job.launch(runId, command.salesFilePath(), command.settlementFilePath());
        } catch (RuntimeException e) {
            runs.markFailed(runId, e.getMessage());
            throw e;
        }

        log.info("conciliacao {} iniciada para os arquivos {} e {}",
                runId, command.salesFileName(), command.settlementFileName());
        return runId;
    }
}
