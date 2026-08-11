package br.com.bergamin.reconciliation.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Um apontamento da conciliacao.
 *
 * <p>Carrega sempre o valor esperado, o encontrado e a diferenca. Um relatorio que diz
 * apenas "divergencia na transacao X" obriga quem for resolver a abrir os dois arquivos de
 * novo; com os tres numeros na linha, da para decidir na hora se vale abrir chamado com o
 * adquirente.</p>
 *
 * <p>As fabricas estaticas existem para que cada tipo nasca com os campos coerentes -- nao
 * ha como criar um {@code VENDA_SEM_REPASSE} com valor encontrado preenchido.</p>
 */
public record Divergence(
        DivergenceType type,
        String transactionId,
        String orderReference,
        Money expectedAmount,
        Money foundAmount,
        Money difference,
        String details) {

    public Divergence {
        Objects.requireNonNull(type, "type e obrigatorio");
        Objects.requireNonNull(transactionId, "transactionId e obrigatorio");
    }

    public static Divergence saleWithoutSettlement(SaleRecord sale) {
        return new Divergence(
                DivergenceType.VENDA_SEM_REPASSE,
                sale.transactionId(),
                sale.orderReference(),
                sale.grossAmount(),
                Money.ZERO,
                Money.ZERO.subtract(sale.grossAmount()),
                "Venda de %s em %s sem repasse correspondente"
                        .formatted(sale.grossAmount(), sale.saleDate()));
    }

    public static Divergence settlementWithoutSale(SettlementRecord settlement) {
        return new Divergence(
                DivergenceType.REPASSE_SEM_VENDA,
                settlement.transactionId(),
                null,
                Money.ZERO,
                settlement.grossAmount(),
                settlement.grossAmount(),
                "Repasse de %s em %s sem venda correspondente no sistema"
                        .formatted(settlement.grossAmount(), settlement.settlementDate()));
    }

    public static Divergence amountMismatch(SaleRecord sale, SettlementRecord settlement) {
        Money difference = settlement.grossAmount().subtract(sale.grossAmount());
        return new Divergence(
                DivergenceType.VALOR_DIVERGENTE,
                sale.transactionId(),
                sale.orderReference(),
                sale.grossAmount(),
                settlement.grossAmount(),
                difference,
                "Venda registrada como %s, repassada como %s"
                        .formatted(sale.grossAmount(), settlement.grossAmount()));
    }

    public static Divergence feeAboveContract(SaleRecord sale, SettlementRecord settlement,
                                              BigDecimal contractedRate, Money expectedFee) {
        return new Divergence(
                DivergenceType.TAXA_ACIMA_DO_CONTRATADO,
                sale.transactionId(),
                sale.orderReference(),
                expectedFee,
                settlement.feeAmount(),
                settlement.feeAmount().subtract(expectedFee),
                "Taxa de %s%% cobrada em %s, contratada %s%% para %s"
                        .formatted(settlement.effectiveRate().toPlainString(),
                                settlement.feeAmount(),
                                contractedRate.toPlainString(),
                                sale.paymentMethod()));
    }

    public static Divergence duplicateSettlement(SettlementRecord duplicate, int occurrences) {
        return new Divergence(
                DivergenceType.REPASSE_DUPLICADO,
                duplicate.transactionId(),
                null,
                duplicate.grossAmount(),
                duplicate.grossAmount().add(duplicate.grossAmount()),
                duplicate.grossAmount(),
                "Transacao aparece %d vezes no arquivo de repasse".formatted(occurrences));
    }

    public static Divergence inconsistentNet(SettlementRecord settlement) {
        Money expected = settlement.grossAmount().subtract(settlement.feeAmount());
        return new Divergence(
                DivergenceType.LIQUIDO_INCONSISTENTE,
                settlement.transactionId(),
                null,
                expected,
                settlement.netAmount(),
                settlement.netAmount().subtract(expected),
                "Liquido informado %s, esperado %s (bruto %s menos taxa %s)"
                        .formatted(settlement.netAmount(), expected,
                                settlement.grossAmount(), settlement.feeAmount()));
    }

    public boolean isCritical() {
        return type.isCritical();
    }
}
