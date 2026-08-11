package br.com.bergamin.reconciliation.domain.service;

import br.com.bergamin.reconciliation.domain.model.Divergence;
import br.com.bergamin.reconciliation.domain.model.DivergenceType;
import br.com.bergamin.reconciliation.domain.model.FeeSchedule;
import br.com.bergamin.reconciliation.domain.model.Money;
import br.com.bergamin.reconciliation.domain.model.PaymentMethod;
import br.com.bergamin.reconciliation.domain.model.SaleRecord;
import br.com.bergamin.reconciliation.domain.model.SettlementRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReconciliationEngine")
class ReconciliationEngineTest {

    private static final LocalDate DIA = LocalDate.of(2026, 8, 10);

    // Taxas fechadas com o adquirente: 3,19% no credito, 1,99% no debito.
    private final FeeSchedule taxas = new FeeSchedule(Map.of(
            PaymentMethod.CREDITO, new BigDecimal("3.19"),
            PaymentMethod.DEBITO, new BigDecimal("1.99")));

    private final ReconciliationEngine engine = new ReconciliationEngine(taxas);

    private SaleRecord venda(String valor) {
        return new SaleRecord("TX-1", "PED-100", DIA, Money.of(valor), PaymentMethod.CREDITO, 1);
    }

    private SettlementRecord repasse(String bruto, String taxa, String liquido) {
        return new SettlementRecord("TX-1", DIA.plusDays(30),
                Money.of(bruto), Money.of(taxa), Money.of(liquido));
    }

    @Test
    @DisplayName("venda que bate com o repasse nao gera apontamento")
    void semDivergencia() {
        // 1000,00 com taxa de 3,19% = 31,90 de taxa, 968,10 liquido.
        List<Divergence> divergencias = engine.compare(
                venda("1000.00"), List.of(repasse("1000.00", "31.90", "968.10")));

        assertThat(divergencias).isEmpty();
    }

    @Test
    @DisplayName("venda sem linha no repasse: o dinheiro nao entrou")
    void vendaSemRepasse() {
        List<Divergence> divergencias = engine.compare(venda("1000.00"), List.of());

        assertThat(divergencias).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(DivergenceType.VENDA_SEM_REPASSE);
            assertThat(d.isCritical()).isTrue();
            assertThat(d.expectedAmount()).isEqualTo(Money.of("1000.00"));
            assertThat(d.difference()).isEqualTo(Money.of("-1000.00"));
        });
    }

    @Test
    @DisplayName("valor bruto diferente aponta a diferenca exata")
    void valorDivergente() {
        List<Divergence> divergencias = engine.compare(
                venda("1000.00"), List.of(repasse("900.00", "28.71", "871.29")));

        assertThat(divergencias)
                .extracting(Divergence::type)
                .contains(DivergenceType.VALOR_DIVERGENTE);

        Divergence divergencia = divergencias.stream()
                .filter(d -> d.type() == DivergenceType.VALOR_DIVERGENTE)
                .findFirst().orElseThrow();
        assertThat(divergencia.difference()).isEqualTo(Money.of("-100.00"));
    }

    @Test
    @DisplayName("diferenca de um centavo e arredondamento, nao divergencia")
    void toleraUmCentavo() {
        // O arquivo do adquirente arredonda; acusar isso encheria o relatorio de ruido.
        List<Divergence> divergencias = engine.compare(
                venda("1000.00"), List.of(repasse("1000.01", "31.90", "968.11")));

        assertThat(divergencias).isEmpty();
    }

    @Test
    @DisplayName("taxa acima da contratada e detectada mesmo com o valor correto")
    void taxaAcimaDoContrato() {
        // Valor bruto certo, mas cobraram 4,50% onde o contrato diz 3,19%.
        List<Divergence> divergencias = engine.compare(
                venda("1000.00"), List.of(repasse("1000.00", "45.00", "955.00")));

        assertThat(divergencias).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(DivergenceType.TAXA_ACIMA_DO_CONTRATADO);
            assertThat(d.expectedAmount()).isEqualTo(Money.of("31.90"));
            assertThat(d.foundAmount()).isEqualTo(Money.of("45.00"));
            // R$ 13,10 a mais nesta venda. Multiplicado por milhares de vendas, e o que
            // paga o projeto.
            assertThat(d.difference()).isEqualTo(Money.of("13.10"));
        });
    }

    @Test
    @DisplayName("variacao minima de taxa nao vira apontamento")
    void toleraVariacaoMinimaDeTaxa() {
        // 3,20% contra 3,19% contratado: dentro da folga de 0,05 ponto.
        List<Divergence> divergencias = engine.compare(
                venda("1000.00"), List.of(repasse("1000.00", "32.00", "968.00")));

        assertThat(divergencias).isEmpty();
    }

    @Test
    @DisplayName("mesma transacao repassada duas vezes")
    void repasseDuplicado() {
        SettlementRecord linha = repasse("1000.00", "31.90", "968.10");

        List<Divergence> divergencias = engine.compare(venda("1000.00"), List.of(linha, linha));

        assertThat(divergencias).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(DivergenceType.REPASSE_DUPLICADO);
            assertThat(d.details()).contains("2 vezes");
        });
    }

    @Test
    @DisplayName("linha do adquirente que nao fecha consigo mesma")
    void liquidoInconsistente() {
        // 1000,00 - 31,90 deveria dar 968,10, nao 900,00.
        List<Divergence> divergencias = engine.compare(
                venda("1000.00"), List.of(repasse("1000.00", "31.90", "900.00")));

        assertThat(divergencias).singleElement().satisfies(d ->
                assertThat(d.type()).isEqualTo(DivergenceType.LIQUIDO_INCONSISTENTE));
    }

    @Test
    @DisplayName("uma linha pode ter mais de um problema ao mesmo tempo")
    void acumulaDivergencias() {
        // Valor errado e taxa abusiva na mesma transacao.
        List<Divergence> divergencias = engine.compare(
                venda("1000.00"), List.of(repasse("800.00", "60.00", "740.00")));

        assertThat(divergencias)
                .extracting(Divergence::type)
                .containsExactlyInAnyOrder(
                        DivergenceType.VALOR_DIVERGENTE,
                        DivergenceType.TAXA_ACIMA_DO_CONTRATADO);
    }

    @Test
    @DisplayName("meio de pagamento sem taxa contratada nao gera falso positivo")
    void ignoraTaxaQuandoNaoHaContrato() {
        SaleRecord vendaPix = new SaleRecord("TX-2", "PED-200", DIA,
                Money.of("500.00"), PaymentMethod.PIX, 1);
        SettlementRecord repassePix = new SettlementRecord("TX-2", DIA,
                Money.of("500.00"), Money.of("50.00"), Money.of("450.00"));

        // Sem taxa de PIX na tabela, o motor nao tem base para comparar -- e nao inventa uma.
        assertThat(engine.compare(vendaPix, List.of(repassePix))).isEmpty();
    }

    @Test
    @DisplayName("repasse sem venda correspondente")
    void repasseSemVenda() {
        Divergence divergencia = Divergence.settlementWithoutSale(
                repasse("300.00", "9.57", "290.43"));

        assertThat(divergencia.type()).isEqualTo(DivergenceType.REPASSE_SEM_VENDA);
        assertThat(divergencia.foundAmount()).isEqualTo(Money.of("300.00"));
    }
}
