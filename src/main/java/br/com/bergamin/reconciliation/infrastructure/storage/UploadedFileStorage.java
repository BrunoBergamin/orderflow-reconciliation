package br.com.bergamin.reconciliation.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    public StoredFile store(MultipartFile file, String prefix) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("arquivo de %s ausente ou vazio".formatted(prefix));
        }

        try {
            Files.createDirectories(directory);
            Path destination = directory.resolve("%s-%s.csv".formatted(prefix, UUID.randomUUID()));
            String hash = copyCalculatingHash(file, destination);

            return new StoredFile(destination.toAbsolutePath().toString(), file.getOriginalFilename(), hash);
        } catch (IOException e) {
            throw new IllegalStateException("nao foi possivel gravar o arquivo de " + prefix, e);
        }
    }

    /**
     * Grava e calcula o SHA-256 na mesma passada.
     *
     * <p>Ler o arquivo duas vezes (uma para gravar, outra para o hash) dobraria a I/O de um
     * arquivo de fechamento que pode ter dezenas de megabytes. O {@link DigestOutputStream}
     * calcula enquanto os bytes passam.</p>
     */
    private String copyCalculatingHash(MultipartFile file, Path destination) throws IOException {
        MessageDigest digest = sha256();

        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(destination);
             DigestOutputStream digestOutput = new DigestOutputStream(output, digest)) {
            input.transferTo(digestOutput);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 e obrigatorio em toda JVM; se faltar, o ambiente esta quebrado.
            throw new IllegalStateException("SHA-256 indisponivel nesta JVM", e);
        }
    }
}
