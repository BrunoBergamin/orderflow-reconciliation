package br.com.bergamin.reconciliation.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI reconciliationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OrderFlow Reconciliation API")
                        .version("1.0.0")
                        .description("""
                                Conciliacao financeira em lote. Recebe o relatorio de vendas da loja e o
                                arquivo de repasse do adquirente, cruza transacao a transacao e aponta o
                                que nao fecha -- inclusive taxa cobrada acima da contratada.

                                Processamento com Spring Batch: tolera linha ruim sem derrubar o arquivo
                                e o job reinicia do passo onde parou.
                                """)
                        .contact(new Contact()
                                .name("Bruno Alves Bergamin")
                                .url("https://www.linkedin.com/in/bruno-alves-bergamin-6b711a347"))
                        .license(new License().name("MIT")));
    }
}
