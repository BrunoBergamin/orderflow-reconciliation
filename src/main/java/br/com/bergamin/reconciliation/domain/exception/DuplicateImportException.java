package br.com.bergamin.reconciliation.domain.exception;

import java.util.UUID;

/**
 * Os mesmos dois arquivos ja foram conciliados antes. Vira HTTP 409.
 *
 * <p>Nao e um erro: e um aviso com o id da execucao anterior, para o operador olhar o
 * resultado que ja existe em vez de gerar um segundo identico. Quem quiser reprocessar
 * mesmo assim repete a chamada com {@code force=true}.</p>
 */
public class DuplicateImportException extends RuntimeException {

    private final UUID previousRunId;

    public DuplicateImportException(UUID previousRunId) {
        super("estes arquivos ja foram conciliados na execucao " + previousRunId);
        this.previousRunId = previousRunId;
    }

    public UUID getPreviousRunId() {
        return previousRunId;
    }
}
