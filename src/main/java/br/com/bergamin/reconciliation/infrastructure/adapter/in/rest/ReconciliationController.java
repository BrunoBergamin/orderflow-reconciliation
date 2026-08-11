package br.com.bergamin.reconciliation.infrastructure.adapter.in.rest;

import br.com.bergamin.reconciliation.application.port.in.FindReconciliationUseCase;
import br.com.bergamin.reconciliation.application.port.in.StartReconciliationUseCase;
import br.com.bergamin.reconciliation.domain.model.DivergenceType;
import br.com.bergamin.reconciliation.infrastructure.adapter.in.rest.dto.ReconciliationDtos;
import br.com.bergamin.reconciliation.infrastructure.storage.UploadedFileStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reconciliations")
@Validated
@Tag(name = "Conciliacao", description = "Importacao dos arquivos e consulta dos apontamentos")
public class ReconciliationController {

    private final StartReconciliationUseCase startReconciliation;
    private final FindReconciliationUseCase findReconciliation;
    private final UploadedFileStorage storage;

    public ReconciliationController(StartReconciliationUseCase startReconciliation,
                                    FindReconciliationUseCase findReconciliation,
                                    UploadedFileStorage storage) {
        this.startReconciliation = startReconciliation;
        this.findReconciliation = findReconciliation;
        this.storage = storage;
    }

    /**
     * Recebe os dois arquivos e devolve 202 com o id da execucao.
     *
     * <p>202 e nao 200 de proposito: o processamento ainda nao terminou quando a resposta
     * sai. Arquivo de fechamento leva minutos, e segurar a conexao ate o fim garantiria
     * timeout no cliente com o job rodando sem ninguem para receber o resultado.</p>
     */
    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "Envia os arquivos e inicia a conciliacao",
            description = """
                    Dois CSV separados por ponto e virgula, com cabecalho.

                    **vendas:** transaction_id;order_reference;sale_date;gross_amount;payment_method;installments
                    **repasse:** transaction_id;settlement_date;gross_amount;fee_amount;net_amount
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Conciliacao aceita e em processamento"),
            @ApiResponse(responseCode = "400", description = "Arquivo ausente ou vazio")
    })
    public ResponseEntity<ReconciliationDtos.StartedResponse> start(
            @RequestParam("salesFile") MultipartFile salesFile,
            @RequestParam("settlementFile") MultipartFile settlementFile,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate) {

        String salesPath = storage.store(salesFile, "vendas");
        String settlementPath = storage.store(settlementFile, "repasse");

        UUID runId = startReconciliation.start(new StartReconciliationUseCase.Command(
                salesPath, settlementPath,
                salesFile.getOriginalFilename(), settlementFile.getOriginalFilename(),
                referenceDate));

        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/reconciliations/" + runId))
                .body(new ReconciliationDtos.StartedResponse(runId, "EM_ANDAMENTO",
                        "/api/v1/reconciliations/" + runId));
    }

    @GetMapping("/{runId}")
    @Operation(summary = "Consulta o resultado de uma conciliacao")
    public ResponseEntity<ReconciliationDtos.RunResponse> findById(@PathVariable UUID runId) {
        return ResponseEntity.ok(ReconciliationDtos.RunResponse.from(findReconciliation.findRun(runId)));
    }

    @GetMapping
    @Operation(summary = "Lista as conciliacoes mais recentes")
    public ResponseEntity<List<ReconciliationDtos.RunResponse>> list(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {

        return ResponseEntity.ok(findReconciliation.listRuns(limit).stream()
                .map(ReconciliationDtos.RunResponse::from)
                .toList());
    }

    @GetMapping("/{runId}/divergences")
    @Operation(summary = "Lista os apontamentos, criticos primeiro e por valor decrescente")
    public ResponseEntity<List<ReconciliationDtos.DivergenceResponse>> divergences(
            @PathVariable UUID runId,
            @RequestParam(required = false) DivergenceType type,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {

        return ResponseEntity.ok(findReconciliation.findDivergences(runId, type, limit, offset).stream()
                .map(ReconciliationDtos.DivergenceResponse::from)
                .toList());
    }

    @GetMapping("/{runId}/import-errors")
    @Operation(summary = "Linhas que nao puderam ser lidas",
            description = "Nenhuma linha e descartada em silencio: o que o parser rejeitou fica aqui.")
    public ResponseEntity<List<FindReconciliationUseCase.ImportErrorView>> importErrors(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {

        return ResponseEntity.ok(findReconciliation.findImportErrors(runId, limit));
    }
}
