package br.com.bergamin.reconciliation.infrastructure.storage;

/**
 * Arquivo recebido e gravado em disco.
 *
 * @param path         caminho local, com nome gerado; e o que o job le
 * @param originalName nome enviado pelo cliente, guardado so para exibicao
 * @param sha256       impressao digital do conteudo, usada para detectar reimportacao
 */
public record StoredFile(String path, String originalName, String sha256) {
}
