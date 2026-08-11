package br.com.bergamin.reconciliation.infrastructure.adapter.in.rest;

import br.com.bergamin.reconciliation.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A API de ponta a ponta: envia os dois arquivos, processa e consulta o resultado.
 *
 * <p>No perfil de teste o job roda de forma sincrona, entao ao voltar do POST a conciliacao
 * ja terminou. Em producao a resposta sai antes, com status EM_ANDAMENTO.</p>
 */
@AutoConfigureMockMvc
@DisplayName("API de conciliacao (integracao)")
class ReconciliationApiIT extends AbstractIntegrationTest {

    private static final String VENDAS = """
            transaction_id;order_reference;sale_date;gross_amount;payment_method;installments
            TX-A;PED-A;2026-08-01;1000.00;CREDITO;1
            TX-B;PED-B;2026-08-01;400.00;CREDITO;1
            """;

    private static final String REPASSE = """
            transaction_id;settlement_date;gross_amount;fee_amount;net_amount
            TX-A;2026-08-31;1000.00;31.90;968.10
            TX-B;2026-08-31;400.00;40.00;360.00
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void prepararCenario() {
        limparBanco();
    }

    @Test
    @DisplayName("envia os arquivos, processa e devolve o resumo com os apontamentos")
    void fluxoCompleto() throws Exception {
        MvcResult inicio = mockMvc.perform(multipart("/api/v1/reconciliations")
                        .file(arquivo("salesFile", "vendas.csv", VENDAS))
                        .file(arquivo("settlementFile", "repasse.csv", REPASSE))
                        .param("referenceDate", "2026-08-31"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andReturn();

        String runId = objectMapper.readTree(inicio.getResponse().getContentAsString())
                .get("runId").asText();

        mockMvc.perform(get("/api/v1/reconciliations/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONCLUIDA"))
                .andExpect(jsonPath("$.salesFile").value("vendas.csv"))
                .andExpect(jsonPath("$.summary.salesRead").value(2))
                .andExpect(jsonPath("$.summary.matched").value(1))
                .andExpect(jsonPath("$.summary.divergences").value(1))
                // TX-B: 400,00 a 10% de taxa contra 3,19% contratados = 27,24 a mais.
                .andExpect(jsonPath("$.summary.amountAtRisk").value(27.24))
                .andExpect(jsonPath("$.summary.matchRate").value(50.00));

        mockMvc.perform(get("/api/v1/reconciliations/" + runId + "/divergences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("TAXA_ACIMA_DO_CONTRATADO"))
                .andExpect(jsonPath("$[0].severity").value("CRITICA"))
                .andExpect(jsonPath("$[0].transactionId").value("TX-B"))
                .andExpect(jsonPath("$[0].description").isNotEmpty());
    }

    @Test
    @DisplayName("filtra os apontamentos por tipo")
    void filtraPorTipo() throws Exception {
        String runId = iniciarConciliacao();

        mockMvc.perform(get("/api/v1/reconciliations/" + runId + "/divergences")
                        .param("type", "VENDA_SEM_REPASSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/v1/reconciliations/" + runId + "/divergences")
                        .param("type", "TAXA_ACIMA_DO_CONTRATADO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("reenviar os mesmos arquivos avisa e aponta a conciliacao anterior")
    void recusaImportacaoDuplicada() throws Exception {
        String primeiroRunId = iniciarConciliacao();

        // Mesmo conteudo, nome diferente: a deteccao e por hash, nao por nome de arquivo.
        mockMvc.perform(multipart("/api/v1/reconciliations")
                        .file(arquivo("salesFile", "vendas (1).csv", VENDAS))
                        .file(arquivo("settlementFile", "repasse - copia.csv", REPASSE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Arquivos ja conciliados"))
                .andExpect(jsonPath("$.previousRunId").value(primeiroRunId))
                .andExpect(jsonPath("$.comoReprocessar").isNotEmpty());

        // Nao criou uma segunda execucao.
        assertThat(contarExecucoes()).isEqualTo(1);
    }

    @Test
    @DisplayName("com force=true reprocessa mesmo assim")
    void forcaReprocessamento() throws Exception {
        iniciarConciliacao();

        mockMvc.perform(multipart("/api/v1/reconciliations")
                        .file(arquivo("salesFile", "vendas.csv", VENDAS))
                        .file(arquivo("settlementFile", "repasse.csv", REPASSE))
                        .param("force", "true"))
                .andExpect(status().isAccepted());

        // Reimportar as vezes e legitimo: corrigir a tabela de taxas e rodar de novo.
        assertThat(contarExecucoes()).isEqualTo(2);
    }

    @Test
    @DisplayName("arquivo diferente nao e tratado como duplicata")
    void arquivoDiferentePassa() throws Exception {
        iniciarConciliacao();

        mockMvc.perform(multipart("/api/v1/reconciliations")
                        .file(arquivo("salesFile", "vendas.csv", VENDAS))
                        .file(arquivo("settlementFile", "repasse.csv", REPASSE + "TX-C;2026-08-31;10.00;0.32;9.68\n")))
                .andExpect(status().isAccepted());

        assertThat(contarExecucoes()).isEqualTo(2);
    }

    @Test
    @DisplayName("arquivo ausente devolve 400, nao 500")
    void arquivoAusente() throws Exception {
        mockMvc.perform(multipart("/api/v1/reconciliations")
                        .file(arquivo("salesFile", "vendas.csv", VENDAS))
                        .file(arquivo("settlementFile", "vazio.csv", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requisicao invalida"));
    }

    @Test
    @DisplayName("conciliacao inexistente devolve 404")
    void conciliacaoInexistente() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliations/" + java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso nao encontrado"));
    }

    @Test
    @DisplayName("lista as conciliacoes mais recentes")
    void listaRecentes() throws Exception {
        iniciarConciliacao();

        mockMvc.perform(get("/api/v1/reconciliations").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    private String iniciarConciliacao() throws Exception {
        MvcResult resultado = mockMvc.perform(multipart("/api/v1/reconciliations")
                        .file(arquivo("salesFile", "vendas.csv", VENDAS))
                        .file(arquivo("settlementFile", "repasse.csv", REPASSE)))
                .andExpect(status().isAccepted())
                .andReturn();

        String runId = objectMapper.readTree(resultado.getResponse().getContentAsString())
                .get("runId").asText();
        assertThat(runId).isNotBlank();
        return runId;
    }

    private long contarExecucoes() {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_run", Long.class);
        return total == null ? 0 : total;
    }

    private MockMultipartFile arquivo(String campo, String nome, String conteudo) {
        return new MockMultipartFile(campo, nome, "text/csv",
                conteudo.getBytes(StandardCharsets.UTF_8));
    }
}
