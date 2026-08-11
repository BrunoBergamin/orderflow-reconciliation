package br.com.bergamin.reconciliation.domain.model;

/** Meio de pagamento, que determina a taxa contratada. */
public enum PaymentMethod {

    CREDITO,
    CREDITO_PARCELADO,
    DEBITO,
    PIX,
    BOLETO;

    public static PaymentMethod fromFile(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("meio de pagamento vazio");
        }
        return PaymentMethod.valueOf(value.trim().toUpperCase());
    }
}
