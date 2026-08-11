package br.com.bergamin.reconciliation.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Uma execucao de conciliacao e seu resultado. */
public record ReconciliationRun(
        UUID id,
        String salesFile,
        String settlementFile,
        FileFingerprint fingerprint,
        LocalDate referenceDate,
        RunStatus status,
        Instant startedAt,
        Instant finishedAt,
        ReconciliationSummary summary,
        String failureReason) {

    /**
     * Impressao digital do par de arquivos.
     *
     * <p>Identifica a conciliacao pelo <b>conteudo</b>, e nao pelo nome: o mesmo arquivo
     * salvo como "repasse.csv" e "repasse (1).csv" continua sendo o mesmo arquivo.</p>
     */
    public record FileFingerprint(String salesHash, String settlementHash) {

        public static final FileFingerprint NONE = new FileFingerprint(null, null);

        public boolean isPresent() {
            return salesHash != null && settlementHash != null;
        }
    }

    public static ReconciliationRun starting(UUID id, String salesFile, String settlementFile,
                                             FileFingerprint fingerprint, LocalDate referenceDate,
                                             Instant startedAt) {
        return new ReconciliationRun(id, salesFile, settlementFile, fingerprint, referenceDate,
                RunStatus.EM_ANDAMENTO, startedAt, null, ReconciliationSummary.empty(), null);
    }
}
