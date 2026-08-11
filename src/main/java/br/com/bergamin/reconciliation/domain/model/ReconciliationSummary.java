package br.com.bergamin.reconciliation.domain.model;

/**
 * Resumo de uma execucao de conciliacao.
 *
 * <p>{@code amountAtRisk} soma o dinheiro envolvido nas divergencias criticas, e o numero
 * que interessa a quem paga a conta.
 */
public record ReconciliationSummary(
        long salesRead,
        long settlementsRead,
        long matched,
        long divergences,
        long criticalDivergences,
        Money amountAtRisk) {

    public static ReconciliationSummary empty() {
        return new ReconciliationSummary(0, 0, 0, 0, 0, Money.ZERO);
    }

    public boolean isClean() {
        return divergences == 0;
    }

    /** Percentual de linhas que fecharam sem apontamento. */
    public java.math.BigDecimal matchRate() {
        if (salesRead == 0) {
            return java.math.BigDecimal.ZERO;
        }
        return java.math.BigDecimal.valueOf(matched)
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(salesRead), 2, java.math.RoundingMode.HALF_UP);
    }
}
