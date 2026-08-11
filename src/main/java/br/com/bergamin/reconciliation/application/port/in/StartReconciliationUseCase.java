package br.com.bergamin.reconciliation.application.port.in;

import br.com.bergamin.reconciliation.domain.model.ReconciliationRun;

import java.time.LocalDate;
import java.util.UUID;

/** Caso de uso: iniciar a conciliacao de um par de arquivos. */
public interface StartReconciliationUseCase {

    /**
     * Registra a execucao e dispara o processamento em segundo plano.
     *
     * @return o id da execucao, para acompanhar pelo endpoint de consulta
     * @throws br.com.bergamin.reconciliation.domain.exception.DuplicateImportException
     *         se os mesmos arquivos ja tiverem sido conciliados e {@code force} for falso
     */
    UUID start(Command command);

    /**
     * @param force reprocessa mesmo que os arquivos ja tenham sido conciliados. Existe
     *              porque reimportar as vezes e legitimo -- corrigir a tabela de taxas e
     *              rodar de novo, por exemplo.
     */
    record Command(String salesFilePath, String settlementFilePath,
                   String salesFileName, String settlementFileName,
                   ReconciliationRun.FileFingerprint fingerprint,
                   LocalDate referenceDate,
                   boolean force) {
    }
}
