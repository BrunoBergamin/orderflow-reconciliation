package br.com.bergamin.reconciliation.infrastructure.adapter.out.persistence;

import br.com.bergamin.reconciliation.application.port.in.FindReconciliationUseCase;
import br.com.bergamin.reconciliation.application.port.out.ReconciliationRunRepositoryPort;
import br.com.bergamin.reconciliation.domain.model.Divergence;
import br.com.bergamin.reconciliation.domain.model.DivergenceType;
import br.com.bergamin.reconciliation.domain.model.Money;
import br.com.bergamin.reconciliation.domain.model.ReconciliationRun;
import br.com.bergamin.reconciliation.domain.model.ReconciliationSummary;
import br.com.bergamin.reconciliation.domain.model.RunStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Acesso a dados em JDBC puro: consultas de relatorio, sem mapeamento objeto-relacional. */
@Component
public class JdbcReconciliationRepositoryAdapter implements ReconciliationRunRepositoryPort {

    private static final String RUN_COLUMNS = """
            id, sales_file, settlement_file, sales_file_hash, settlement_file_hash,
            reference_date, status, started_at, finished_at,
            sales_read, settlements_read, matched, divergences, critical_divergences,
            amount_at_risk, failure_reason
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcReconciliationRepositoryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(ReconciliationRun run) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", run.id());
        params.put("salesFile", run.salesFile());
        params.put("settlementFile", run.settlementFile());
        params.put("referenceDate", run.referenceDate());
        params.put("status", run.status().name());
        params.put("startedAt", Timestamp.from(run.startedAt()));
        params.put("salesHash", run.fingerprint().salesHash());
        params.put("settlementHash", run.fingerprint().settlementHash());

        jdbcTemplate.update("""
                INSERT INTO reconciliation_run
                    (id, sales_file, settlement_file, sales_file_hash, settlement_file_hash,
                     reference_date, status, started_at)
                VALUES (:id, :salesFile, :settlementFile, :salesHash, :settlementHash,
                        :referenceDate, :status, :startedAt)
                """, params);
    }

    /**
     * Ultima execucao concluida com os mesmos dois arquivos.
     *
     * <p>So considera {@code CONCLUIDA}: uma tentativa anterior que falhou nao e motivo
     * para impedir a nova -- pelo contrario, reimportar e exatamente o que se espera.</p>
     */
    @Override
    public Optional<ReconciliationRun> findConcludedWithSameFiles(
            ReconciliationRun.FileFingerprint fingerprint) {

        if (!fingerprint.isPresent()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + """
                         FROM reconciliation_run
                         WHERE sales_file_hash = :salesHash
                           AND settlement_file_hash = :settlementHash
                           AND status = 'CONCLUIDA'
                         ORDER BY started_at DESC LIMIT 1
                        """,
                Map.of("salesHash", fingerprint.salesHash(),
                        "settlementHash", fingerprint.settlementHash()),
                runMapper()).stream().findFirst();
    }

    @Override
    public Optional<ReconciliationRun> findById(UUID runId) {
        List<ReconciliationRun> found = jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + " FROM reconciliation_run WHERE id = :id",
                Map.of("id", runId), runMapper());
        return found.stream().findFirst();
    }

    @Override
    public List<ReconciliationRun> findRecent(int limit) {
        return jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + " FROM reconciliation_run ORDER BY started_at DESC LIMIT :limit",
                Map.of("limit", limit), runMapper());
    }

    @Override
    public List<Divergence> findDivergences(UUID runId, DivergenceType type, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("limit", limit)
                .addValue("offset", offset);

        // O filtro so entra no SQL quando existe -- nada de "(:type IS NULL OR type = :type)",
        // que atrapalha o planejador e ainda esbarra em inferencia de tipo de parametro nulo.
        StringBuilder sql = new StringBuilder("""
                SELECT type, transaction_id, order_reference, expected_amount,
                       found_amount, difference, details
                FROM divergence WHERE run_id = :runId
                """);
        if (type != null) {
            sql.append(" AND type = :type");
            params.addValue("type", type.name());
        }
        // Criticas primeiro: quem abre o relatorio quer ver dinheiro faltando, nao centavos.
        sql.append(" ORDER BY severity, ABS(COALESCE(difference, 0)) DESC LIMIT :limit OFFSET :offset");

        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new Divergence(
                DivergenceType.valueOf(rs.getString("type")),
                rs.getString("transaction_id"),
                rs.getString("order_reference"),
                money(rs, "expected_amount"),
                money(rs, "found_amount"),
                money(rs, "difference"),
                rs.getString("details")));
    }

    @Override
    public List<FindReconciliationUseCase.ImportErrorView> findImportErrors(UUID runId, int limit) {
        return jdbcTemplate.query("""
                        SELECT source_file, line_number, raw_line, message
                        FROM import_error WHERE run_id = :runId
                        ORDER BY source_file, line_number LIMIT :limit
                        """,
                Map.of("runId", runId, "limit", limit),
                (rs, rowNum) -> new FindReconciliationUseCase.ImportErrorView(
                        rs.getString("source_file"),
                        rs.getObject("line_number", Long.class),
                        rs.getString("raw_line"),
                        rs.getString("message")));
    }

    @Override
    public void markFailed(UUID runId, String reason) {
        jdbcTemplate.update("""
                        UPDATE reconciliation_run
                        SET status = 'FALHOU', finished_at = now(), failure_reason = :reason
                        WHERE id = :id
                        """,
                Map.of("id", runId, "reason", reason == null ? "erro desconhecido" : reason));
    }

    private RowMapper<ReconciliationRun> runMapper() {
        return (rs, rowNum) -> new ReconciliationRun(
                rs.getObject("id", UUID.class),
                rs.getString("sales_file"),
                rs.getString("settlement_file"),
                new ReconciliationRun.FileFingerprint(
                        rs.getString("sales_file_hash"), rs.getString("settlement_file_hash")),
                rs.getDate("reference_date") == null ? null : rs.getDate("reference_date").toLocalDate(),
                RunStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                new ReconciliationSummary(
                        rs.getLong("sales_read"),
                        rs.getLong("settlements_read"),
                        rs.getLong("matched"),
                        rs.getLong("divergences"),
                        rs.getLong("critical_divergences"),
                        Money.of(rs.getBigDecimal("amount_at_risk"))),
                rs.getString("failure_reason"));
    }

    private Money money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : Money.of(value);
    }
}
