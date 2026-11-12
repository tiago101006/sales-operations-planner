package pt.tiagodias.sop.imports;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.ByteBuffer;
import java.util.Set;
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
        FileAttribute<?>[] permissions = Files.getFileStore(root).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        boolean created = false;
        try (var output = Files.newByteChannel(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), permissions)) {
            created = true;
            var buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) { output.write(buffer); }
        } catch (IOException exception) {
            if (created) {
                try { Files.deleteIfExists(file); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            }
            throw exception;
        }
        return file;
    }

    public void discard(Path file) throws IOException { Files.deleteIfExists(file); }
}
