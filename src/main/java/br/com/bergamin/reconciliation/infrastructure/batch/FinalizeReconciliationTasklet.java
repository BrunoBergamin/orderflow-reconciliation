package br.com.bergamin.reconciliation.infrastructure.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Fecha a conciliacao: acha os repasses orfaos e consolida os numeros.
 *
 * <p>Os dois trabalhos sao feitos em SQL, e nao carregando linhas para a aplicacao. Achar
 * repasse sem venda e um anti-join; contar e somar e agregacao. Trazer isso para a memoria
 * da JVM so para percorrer em Java seria mais lento e nao ficaria mais legivel.</p>
 */
@Component
public class FinalizeReconciliationTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(FinalizeReconciliationTasklet.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public FinalizeReconciliationTasklet(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID runId = UUID.fromString((String) chunkContext.getStepContext()
                .getJobParameters().get(ReconciliationJobConfig.PARAM_RUN_ID));
        Map<String, Object> params = Map.of("runId", runId);

        int orphans = insertOrphanSettlements(params);
        consolidateSummary(params);

        log.info("conciliacao {} finalizada, {} repasses sem venda correspondente", runId, orphans);
        return RepeatStatus.FINISHED;
    }

    /** Repasse que chegou sem venda correspondente no sistema. */
    private int insertOrphanSettlements(Map<String, Object> params) {
        return jdbcTemplate.update("""
                INSERT INTO divergence
                    (id, run_id, type, severity, transaction_id, order_reference,
                     expected_amount, found_amount, difference, details)
                SELECT gen_random_uuid(), s.run_id, 'REPASSE_SEM_VENDA', 'ATENCAO',
                       s.transaction_id, NULL, 0, s.gross_amount, s.gross_amount,
                       'Repasse de ' || s.gross_amount || ' em ' || s.settlement_date
                           || ' sem venda correspondente no sistema'
                FROM settlement_record s
                WHERE s.run_id = :runId
                  AND NOT EXISTS (
                      SELECT 1 FROM sale_record v
                      WHERE v.run_id = s.run_id AND v.transaction_id = s.transaction_id)
                """, params);
    }

    private void consolidateSummary(Map<String, Object> params) {
        jdbcTemplate.update("""
                UPDATE reconciliation_run SET
                    sales_read           = (SELECT COUNT(*) FROM sale_record WHERE run_id = :runId),
                    settlements_read     = (SELECT COUNT(*) FROM settlement_record WHERE run_id = :runId),
                    divergences          = (SELECT COUNT(*) FROM divergence WHERE run_id = :runId),
                    critical_divergences = (SELECT COUNT(*) FROM divergence
                                            WHERE run_id = :runId AND severity = 'CRITICA'),
                    -- Dinheiro em jogo: soma do valor absoluto das divergencias criticas.
                    amount_at_risk       = COALESCE((SELECT SUM(ABS(difference)) FROM divergence
                                                     WHERE run_id = :runId AND severity = 'CRITICA'), 0),
                    matched              = (SELECT COUNT(*) FROM sale_record v
                                            WHERE v.run_id = :runId
                                              AND NOT EXISTS (SELECT 1 FROM divergence d
                                                              WHERE d.run_id = v.run_id
                                                                AND d.transaction_id = v.transaction_id)),
                    status               = 'CONCLUIDA',
                    finished_at          = now()
                WHERE id = :runId
                """, params);
    }
}
