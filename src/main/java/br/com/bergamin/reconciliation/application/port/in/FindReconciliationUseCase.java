package br.com.bergamin.reconciliation.application.port.in;

import br.com.bergamin.reconciliation.domain.model.Divergence;
import br.com.bergamin.reconciliation.domain.model.DivergenceType;
import br.com.bergamin.reconciliation.domain.model.ReconciliationRun;

import java.util.List;
import java.util.UUID;

/** Caso de uso: consultar o resultado de uma conciliacao. */
public interface FindReconciliationUseCase {

    ReconciliationRun findRun(UUID runId);

    List<ReconciliationRun> listRuns(int limit);

    List<Divergence> findDivergences(UUID runId, DivergenceType type, int limit, int offset);

    List<ImportErrorView> findImportErrors(UUID runId, int limit);

    /** Linha que o parser nao conseguiu ler, guardada para conferencia manual. */
    record ImportErrorView(String sourceFile, Long lineNumber, String rawLine, String message) {
    }
}
