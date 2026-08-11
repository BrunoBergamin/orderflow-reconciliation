package br.com.bergamin.reconciliation.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Grava o arquivo enviado em disco para o job ler.
 *
 * <p>O job de lote precisa de um caminho estavel, e nao de um {@code MultipartFile} que
 * some quando a requisicao termina -- especialmente com processamento assincrono, em que a
 * resposta HTTP sai antes de o arquivo ser lido.</p>
 *
 * <p>O nome gravado e sempre um UUID. O nome original vem do cliente e nao pode ser usado
 * para montar caminho: {@code ../../etc/passwd} num campo de upload e o ataque de path
 * traversal mais antigo que existe. O nome original e guardado no banco, apenas para
 * exibicao.</p>
 */
@Component
public class UploadedFileStorage {

    private final Path directory;

    public UploadedFileStorage(@Value("${reconciliation.storage.directory:${java.io.tmpdir}/orderflow-reconciliation}")
                               String directory) {
        this.directory = Paths.get(directory);
    }

    public String store(MultipartFile file, String prefix) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("arquivo de %s ausente ou vazio".formatted(prefix));
        }

        try {
            Files.createDirectories(directory);
            Path destination = directory.resolve("%s-%s.csv".formatted(prefix, UUID.randomUUID()));

            try (InputStream input = file.getInputStream()) {
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("nao foi possivel gravar o arquivo de " + prefix, e);
        }
    }
}
