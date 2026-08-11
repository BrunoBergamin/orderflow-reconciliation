package br.com.bergamin.reconciliation.infrastructure.config;

import br.com.bergamin.reconciliation.domain.model.FeeSchedule;
import br.com.bergamin.reconciliation.domain.model.PaymentMethod;
import br.com.bergamin.reconciliation.domain.service.ReconciliationEngine;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

@Configuration
public class ApplicationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Taxas contratadas, vindas de configuracao.
     *
     * <p>Ficam fora do codigo porque mudam por negociacao comercial, nao por release: quando
     * a loja renegocia a taxa do credito, isso e uma variavel de ambiente, nao um deploy.</p>
     */
    @Bean
    public FeeSchedule feeSchedule(FeeProperties properties) {
        Map<PaymentMethod, BigDecimal> rates = new EnumMap<>(PaymentMethod.class);
        properties.getRates().forEach((method, rate) ->
                rates.put(PaymentMethod.fromFile(method), new BigDecimal(rate)));
        return new FeeSchedule(rates);
    }

    @Bean
    public ReconciliationEngine reconciliationEngine(FeeSchedule feeSchedule) {
        return new ReconciliationEngine(feeSchedule);
    }

    @ConfigurationProperties(prefix = "reconciliation.fees")
    public static class FeeProperties {

        /** Taxa contratada por meio de pagamento, em pontos percentuais. */
        private Map<String, String> rates = new java.util.LinkedHashMap<>();

        public Map<String, String> getRates() {
            return rates;
        }

        public void setRates(Map<String, String> rates) {
            this.rates = rates;
        }
    }

    @Bean
    public FeeProperties feeProperties() {
        return new FeeProperties();
    }
}
