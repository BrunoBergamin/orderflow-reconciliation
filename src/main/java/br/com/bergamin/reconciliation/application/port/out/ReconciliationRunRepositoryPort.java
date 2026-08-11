package br.com.bergamin.reconciliation.application.port.out;

import br.com.bergamin.reconciliation.domain.model.Divergence;
import br.com.bergamin.reconciliation.domain.model.DivergenceType;
import br.com.bergamin.reconciliation.domain.model.ReconciliationRun;
import br.com.bergamin.reconciliation.application.port.in.FindReconciliationUseCase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunRepositoryPort {

    void save(ReconciliationRun run);

    Optional<ReconciliationRun> findById(UUID runId);

    List<ReconciliationRun> findRecent(int limit);

    List<Divergence> findDivergences(UUID runId, DivergenceType type, int limit, int offset);

    List<FindReconciliationUseCase.ImportErrorView> findImportErrors(UUID runId, int limit);

    void markFailed(UUID runId, String reason);
}
