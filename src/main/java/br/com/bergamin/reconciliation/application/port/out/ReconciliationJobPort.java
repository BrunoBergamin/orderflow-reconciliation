package br.com.bergamin.reconciliation.application.port.out;

import java.util.UUID;

/**
 * Porta que dispara o processamento em lote.
 *
 * <p>Existe para que o caso de uso nao conheca Spring Batch. Trocar o job por uma fila de
 * workers, ou por uma chamada a um servico externo de processamento, seria escrever outro
 * adaptador -- o caso de uso continua igual.</p>
 */
public interface ReconciliationJobPort {

    void launch(UUID runId, String salesFilePath, String settlementFilePath);
}
