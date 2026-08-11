package br.com.bergamin.reconciliation.domain.exception;

/** Recurso inexistente. Vira HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object identifier) {
        super("%s nao encontrada: %s".formatted(resource, identifier));
    }
}
