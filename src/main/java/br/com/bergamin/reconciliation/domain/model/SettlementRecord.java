package br.com.bergamin.reconciliation.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Uma linha do arquivo de repasse do adquirente.
 *
 * <p>Lado "deles" da conciliacao: o que a maquininha diz que vendeu, quanto cobrou de taxa e
 * quanto vai depositar.</p>
 */
public record SettlementRecord(
        String transactionId,
        LocalDate settlementDate,
        Money grossAmount,
        Money feeAmount,
        Money netAmount) {

    public SettlementRecord {
        Objects.requireNonNull(transactionId, "transactionId e obrigatorio");
        Objects.requireNonNull(settlementDate, "settlementDate e obrigatorio");
        Objects.requireNonNull(grossAmount, "grossAmount e obrigatorio");
        Objects.requireNonNull(feeAmount, "feeAmount e obrigatorio");
        Objects.requireNonNull(netAmount, "netAmount e obrigatorio");
        if (transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId nao pode ser vazio");
        }
    }

    /** Taxa efetiva cobrada nesta transacao, em pontos percentuais. */
    public java.math.BigDecimal effectiveRate() {
        return feeAmount.percentOf(grossAmount);
    }

    /** Verdadeiro quando {@code liquido != bruto - taxa}, ou seja, a propria linha nao fecha. */
    public boolean isInternallyInconsistent() {
        return netAmount.differsFrom(grossAmount.subtract(feeAmount));
    }
}
