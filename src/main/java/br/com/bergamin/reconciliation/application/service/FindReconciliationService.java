package br.com.bergamin.reconciliation.application.service;

import br.com.bergamin.reconciliation.application.port.in.FindReconciliationUseCase;
import br.com.bergamin.reconciliation.application.port.out.ReconciliationRunRepositoryPort;
import br.com.bergamin.reconciliation.domain.exception.ResourceNotFoundException;
import br.com.bergamin.reconciliation.domain.model.Divergence;
import br.com.bergamin.reconciliation.domain.model.DivergenceType;
import br.com.bergamin.reconciliation.domain.model.ReconciliationRun;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class FindReconciliationService implements FindReconciliationUseCase {

    private final ReconciliationRunRepositoryPort runs;

    public FindReconciliationService(ReconciliationRunRepositoryPort runs) {
        this.runs = runs;
    }

    @Override
    public ReconciliationRun findRun(UUID runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Conciliacao", runId));
    }

    @Override
    public List<ReconciliationRun> listRuns(int limit) {
        return runs.findRecent(limit);
    }

    @Override
    public List<Divergence> findDivergences(UUID runId, DivergenceType type, int limit, int offset) {
        // Confirma que a execucao existe para nao devolver lista vazia de um id inventado.
        findRun(runId);
        return runs.findDivergences(runId, type, limit, offset);
    }

    @Override
    public List<ImportErrorView> findImportErrors(UUID runId, int limit) {
        findRun(runId);
        return runs.findImportErrors(runId, limit);
    }
}
