package br.com.bergamin.reconciliation.infrastructure.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Guarda toda linha que o parser nao conseguiu ler.
 *
 * <p>Pular linha ruim sem registrar seria trocar um problema visivel (job quebrado) por um
 * invisivel (venda que sumiu do relatorio). Aqui a linha pulada vai para
 * {@code import_error} com numero, conteudo original e motivo, e fica consultavel pela
 * API. O operador decide se corrige o arquivo e reprocessa ou se lanca a mao.</p>
 */
public class ImportErrorSkipListener implements SkipListener<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(ImportErrorSkipListener.class);
    private static final int MAX_RAW_LINE = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final UUID runId;
    private final String sourceFile;

    public ImportErrorSkipListener(JdbcTemplate jdbcTemplate, UUID runId, String sourceFile) {
        this.jdbcTemplate = jdbcTemplate;
        this.runId = runId;
        this.sourceFile = sourceFile;
    }

    @Override
    public void onSkipInRead(Throwable throwable) {
        Long lineNumber = null;
        String rawLine = null;

        if (throwable instanceof FlatFileParseException parseException) {
            lineNumber = (long) parseException.getLineNumber();
            rawLine = parseException.getInput();
        }
        record(lineNumber, rawLine, rootMessage(throwable));
    }

    @Override
    public void onSkipInWrite(Object item, Throwable throwable) {
        record(null, String.valueOf(item), rootMessage(throwable));
    }

    @Override
    public void onSkipInProcess(Object item, Throwable throwable) {
        record(null, String.valueOf(item), rootMessage(throwable));
    }

    private void record(Long lineNumber, String rawLine, String message) {
        log.warn("linha {} do arquivo de {} ignorada: {}", lineNumber, sourceFile, message);
        jdbcTemplate.update("""
                        INSERT INTO import_error (id, run_id, source_file, line_number, raw_line, message, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), runId, sourceFile, lineNumber,
                truncate(rawLine, MAX_RAW_LINE), truncate(message, 500),
                Timestamp.from(Instant.now()));
    }

    /** A causa raiz e a mensagem util; o embrulho do Batch so diz "erro ao processar a linha". */
    private String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
