package br.com.bergamin.reconciliation.domain.model;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Tabela de taxas contratadas com o adquirente, por meio de pagamento.
 *
 * <p>E a referencia contra a qual a taxa efetivamente cobrada e comparada. Sem ela, o
 * sistema so consegue dizer "o valor bateu"; com ela, consegue dizer "o valor bateu, mas
 * voce esta pagando 0,4 ponto a mais do que contratou", que costuma ser a divergencia
 * mais cara e a que ninguem percebe, porque cada linha isolada parece certa.</p>
 */
public record FeeSchedule(Map<PaymentMethod, BigDecimal> ratesByMethod) {

    /** Meio ponto percentual de folga sobre a taxa contratada antes de acusar. */
    public static final BigDecimal TOLERANCIA_PONTOS = new BigDecimal("0.05");

    public FeeSchedule {
        ratesByMethod = new EnumMap<>(ratesByMethod);
    }

    public BigDecimal contractedRateFor(PaymentMethod method) {
        BigDecimal rate = ratesByMethod.get(method);
        if (rate == null) {
            throw new IllegalArgumentException("nao ha taxa contratada para " + method);
        }
        return rate;
    }

    public boolean hasRateFor(PaymentMethod method) {
        return ratesByMethod.containsKey(method);
    }

    /** Taxa efetiva acima da contratada, ja descontada a folga de arredondamento. */
    public boolean exceedsContracted(PaymentMethod method, BigDecimal effectiveRate) {
        return effectiveRate.subtract(contractedRateFor(method)).compareTo(TOLERANCIA_PONTOS) > 0;
    }
}
