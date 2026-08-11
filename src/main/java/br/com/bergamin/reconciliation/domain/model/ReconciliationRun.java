package br.com.bergamin.reconciliation.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Uma execucao de conciliacao e seu resultado. */
public record ReconciliationRun(
        UUID id,
        String salesFile,
        String settlementFile,
        LocalDate referenceDate,
        RunStatus status,
        Instant startedAt,
        Instant finishedAt,
        ReconciliationSummary summary,
        String failureReason) {

    public static ReconciliationRun starting(UUID id, String salesFile, String settlementFile,
                                             LocalDate referenceDate, Instant startedAt) {
        return new ReconciliationRun(id, salesFile, settlementFile, referenceDate,
                RunStatus.EM_ANDAMENTO, startedAt, null, ReconciliationSummary.empty(), null);
    }
}
