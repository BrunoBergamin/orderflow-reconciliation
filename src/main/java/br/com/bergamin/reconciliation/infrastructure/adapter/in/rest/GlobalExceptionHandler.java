package br.com.bergamin.reconciliation.infrastructure.adapter.in.rest;

import br.com.bergamin.reconciliation.domain.exception.DuplicateImportException;
import br.com.bergamin.reconciliation.domain.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** Erros em RFC 7807, no mesmo formato dos outros servicos. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_PREFIX = "https://orderflow.dev/errors/";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Recurso nao encontrado", e.getMessage(), "recurso-nao-encontrado");
    }

    /**
     * Nao e erro: e um aviso com o caminho de saida.
     *
     * <p>A resposta carrega o id da execucao anterior para o operador conferir o resultado
     * que ja existe, e diz explicitamente como reprocessar se for essa a intencao.</p>
     */
    @ExceptionHandler(DuplicateImportException.class)
    public ProblemDetail handleDuplicateImport(DuplicateImportException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Arquivos ja conciliados",
                e.getMessage(), "importacao-duplicada");
        problem.setProperty("previousRunId", e.getPreviousRunId().toString());
        problem.setProperty("comoReprocessar", "repita a chamada com force=true");
        return problem;
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class})
    public ProblemDetail handleBadRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Requisicao invalida", e.getMessage(), "requisicao-invalida");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Parametro invalido",
                "Valor invalido para o parametro '%s'.".formatted(e.getName()), "parametro-invalido");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo grande demais",
                "O arquivo enviado excede o limite configurado.", "arquivo-grande");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        String errorId = UUID.randomUUID().toString();
        log.error("erro inesperado [errorId={}]", errorId, e);

        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Informe o errorId ao suporte.", "erro-interno");
        problem.setProperty("errorId", errorId);
        return problem;
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_PREFIX + type));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
