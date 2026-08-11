package br.com.bergamin.reconciliation.domain.service;

import br.com.bergamin.reconciliation.domain.model.Divergence;
import br.com.bergamin.reconciliation.domain.model.FeeSchedule;
import br.com.bergamin.reconciliation.domain.model.Money;
import br.com.bergamin.reconciliation.domain.model.SaleRecord;
import br.com.bergamin.reconciliation.domain.model.SettlementRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compara uma venda com o que o adquirente repassou por ela.
 *
 * <p>Trabalha em uma venda por vez, e nao na lista inteira: e o que permite o job em lote
 * processar arquivo de qualquer tamanho sem carregar tudo na memoria, e ao mesmo tempo
 * deixa a regra testavel sem banco.</p>
 */
public class ReconciliationEngine {

    private final FeeSchedule feeSchedule;

    public ReconciliationEngine(FeeSchedule feeSchedule) {
        this.feeSchedule = Objects.requireNonNull(feeSchedule, "feeSchedule e obrigatorio");
    }

    /**
     * @param settlements todas as linhas de repasse com o mesmo transactionId da venda:
     *                    nenhuma, uma, ou varias (caso de duplicidade)
     */
    public List<Divergence> compare(SaleRecord sale, List<SettlementRecord> settlements) {
        if (settlements.isEmpty()) {
            return List.of(Divergence.saleWithoutSettlement(sale));
        }

        List<Divergence> divergences = new ArrayList<>();

        if (settlements.size() > 1) {
            divergences.add(Divergence.duplicateSettlement(settlements.get(0), settlements.size()));
        }

        // Mesmo duplicada, a primeira linha continua sendo conferida: uma cobranca em
        // duplicidade nao dispensa checar se o valor e a taxa daquela venda estao certos.
        SettlementRecord settlement = settlements.get(0);

        if (sale.grossAmount().differsFrom(settlement.grossAmount())) {
            divergences.add(Divergence.amountMismatch(sale, settlement));
        }
        if (settlement.isInternallyInconsistent()) {
            divergences.add(Divergence.inconsistentNet(settlement));
        }
        checkFee(sale, settlement).ifPresent(divergences::add);

        return List.copyOf(divergences);
    }

    private java.util.Optional<Divergence> checkFee(SaleRecord sale, SettlementRecord settlement) {
        if (!feeSchedule.hasRateFor(sale.paymentMethod()) || settlement.grossAmount().isZero()) {
            return java.util.Optional.empty();
        }

        BigDecimal contracted = feeSchedule.contractedRateFor(sale.paymentMethod());
        if (!feeSchedule.exceedsContracted(sale.paymentMethod(), settlement.effectiveRate())) {
            return java.util.Optional.empty();
        }

        Money expectedFee = Money.of(settlement.grossAmount().amount()
                .multiply(contracted)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));

        return java.util.Optional.of(
                Divergence.feeAboveContract(sale, settlement, contracted, expectedFee));
    }
}
