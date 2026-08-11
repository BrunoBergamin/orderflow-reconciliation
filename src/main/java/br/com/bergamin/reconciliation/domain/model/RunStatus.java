package br.com.bergamin.reconciliation.domain.model;

/** Situacao de uma execucao de conciliacao. */
public enum RunStatus {

    EM_ANDAMENTO,
    CONCLUIDA,
    FALHOU;

    public boolean isFinished() {
        return this != EM_ANDAMENTO;
    }
}
