package br.com.bergamin.reconciliation.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Uma venda como o sistema da loja registrou.
 *
 * <p>Lado "meu" da conciliacao: o que eu acho que vendi.</p>
 */
public record SaleRecord(
        String transactionId,
        String orderReference,
        LocalDate saleDate,
        Money grossAmount,
        PaymentMethod paymentMethod,
        int installments) {

    public SaleRecord {
        Objects.requireNonNull(transactionId, "transactionId e obrigatorio");
        Objects.requireNonNull(saleDate, "saleDate e obrigatorio");
        Objects.requireNonNull(grossAmount, "grossAmount e obrigatorio");
        Objects.requireNonNull(paymentMethod, "paymentMethod e obrigatorio");
        if (transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId nao pode ser vazio");
        }
        if (grossAmount.isNegative() || grossAmount.isZero()) {
            throw new IllegalArgumentException("valor bruto da venda deve ser positivo: " + grossAmount);
        }
        if (installments < 1) {
            throw new IllegalArgumentException("numero de parcelas invalido: " + installments);
        }
    }
}
