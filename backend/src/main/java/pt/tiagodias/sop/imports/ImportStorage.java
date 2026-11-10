package pt.tiagodias.sop.imports;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ImportStorage {
    private final Path root;
    public ImportStorage(@Value("${sop.import.storage-path}") String root) { this.root = Path.of(root).toAbsolutePath().normalize(); }

    public Path save(byte[] data) throws IOException {
        Files.createDirectories(root);
        Path file = root.resolve(UUID.randomUUID() + ".csv");
        Files.write(file, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return file;
    }

    public void discard(Path file) throws IOException { Files.deleteIfExists(file); }
}
