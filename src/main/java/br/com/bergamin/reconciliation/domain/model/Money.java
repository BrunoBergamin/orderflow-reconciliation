package br.com.bergamin.reconciliation.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor monetario em BRL.
 *
 * <p><b>Aceita valores negativos</b>, ao contrario do que faria sentido em um carrinho de
 * compras: aqui um valor representa tambem <i>diferenca</i> de conciliacao, e repasse a
 * menor e exatamente o que este sistema existe para encontrar.</p>
 *
 * <p>Escala fixa em 2 casas com {@code HALF_UP}. Em conciliacao isso nao e detalhe: o
 * arquivo do adquirente ja vem arredondado, e comparar contra um valor de escala diferente
 * geraria divergencia de um centavo em toda linha.</p>
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final Money ZERO = Money.of(BigDecimal.ZERO);

    /**
     * Diferenca de ate um centavo e ruido de arredondamento, nao divergencia.
     *
     * <p>Sem esta tolerancia, um relatorio de 10 mil linhas viria com milhares de alertas de
     * um centavo e ninguem olharia mais para ele -- que e como um sistema de conciliacao
     * morre na pratica.</p>
     */
    public static final Money TOLERANCIA = Money.of("0.01");

    public Money {
        Objects.requireNonNull(amount, "amount nao pode ser nulo");
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    public Money abs() {
        return new Money(this.amount.abs());
    }

    public boolean isZero() {
        return this.amount.signum() == 0;
    }

    public boolean isNegative() {
        return this.amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    /** Verdadeiro quando a diferenca entre os dois passa da tolerancia de arredondamento. */
    public boolean differsFrom(Money other) {
        return subtract(other).abs().isGreaterThan(TOLERANCIA);
    }

    /**
     * Percentual que este valor representa sobre o total, com 4 casas.
     *
     * <p>Usado para achar a taxa efetiva cobrada pelo adquirente e compara-la com a
     * contratada. Quatro casas porque taxa de cartao se negocia em centesimos de ponto
     * (3,19% e 3,20% sao contratos diferentes).</p>
     */
    public BigDecimal percentOf(Money total) {
        if (total.isZero()) {
            return BigDecimal.ZERO;
        }
        return this.amount
                .multiply(BigDecimal.valueOf(100))
                .divide(total.amount, 4, RoundingMode.HALF_UP);
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return "R$ " + amount.toPlainString();
    }
}
