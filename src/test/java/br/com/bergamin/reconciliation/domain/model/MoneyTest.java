package br.com.bergamin.reconciliation.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Money")
class MoneyTest {

    @Test
    @DisplayName("normaliza escala, entao 10.0 e 10.00 sao o mesmo valor")
    void normalizaEscala() {
        assertThat(Money.of("10.0")).isEqualTo(Money.of("10.00"));
    }

    @Test
    @DisplayName("aceita negativo, porque diferenca de conciliacao pode ser a menor")
    void aceitaNegativo() {
        Money diferenca = Money.of("900.00").subtract(Money.of("1000.00"));

        assertThat(diferenca.isNegative()).isTrue();
        assertThat(diferenca.abs()).isEqualTo(Money.of("100.00"));
    }

    @Test
    @DisplayName("differsFrom ignora ate um centavo")
    void toleranciaDeUmCentavo() {
        assertThat(Money.of("100.00").differsFrom(Money.of("100.01"))).isFalse();
        assertThat(Money.of("100.00").differsFrom(Money.of("99.99"))).isFalse();
        assertThat(Money.of("100.00").differsFrom(Money.of("100.02"))).isTrue();
    }

    @Test
    @DisplayName("percentOf calcula a taxa efetiva com 4 casas")
    void taxaEfetiva() {
        // 31,90 sobre 1000,00 = 3,19%
        assertThat(Money.of("31.90").percentOf(Money.of("1000.00")))
                .isEqualByComparingTo("3.1900");

        // Casos assim e que exigem 4 casas: 2,4967% arredondado para 2,50% esconderia
        // a diferenca contra um contrato de 2,49%.
        assertThat(Money.of("18.72").percentOf(Money.of("749.80")))
                .isEqualByComparingTo("2.4967");
    }

    @Test
    @DisplayName("percentual sobre zero nao estoura")
    void percentualSobreZero() {
        assertThat(Money.of("10.00").percentOf(Money.ZERO)).isEqualByComparingTo("0");
    }
}
