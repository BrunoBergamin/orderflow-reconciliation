package br.com.bergamin.reconciliation.application.port.in;

import java.time.LocalDate;
import java.util.UUID;

/** Caso de uso: iniciar a conciliacao de um par de arquivos. */
public interface StartReconciliationUseCase {

    /**
     * Registra a execucao e dispara o processamento em segundo plano.
     *
     * @return o id da execucao, para acompanhar pelo endpoint de consulta
     */
    UUID start(Command command);

    record Command(String salesFilePath, String settlementFilePath,
                   String salesFileName, String settlementFileName,
                   LocalDate referenceDate) {
    }
}
