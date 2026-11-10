package pt.tiagodias.sop.imports;

import java.io.IOException;
import java.nio.file.Path;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static pt.tiagodias.sop.imports.ImportDtos.*;

@Service
public class ImportService {
    public static final long MAX_BYTES = 10 * 1024 * 1024;
    private final HistoricoCsvParser parser;
    private final ImportStorage storage;
    private final ImportWriter writer;
    private final OutcomeCsvParser outcomes;
    private final OutcomeImportWriter outcomeWriter;

    public ImportService(HistoricoCsvParser parser, ImportStorage storage, ImportWriter writer,OutcomeCsvParser outcomes,OutcomeImportWriter outcomeWriter) {
        this.parser = parser; this.storage = storage; this.writer = writer;
        this.outcomes=outcomes;this.outcomeWriter=outcomeWriter;
    }

    public LoteDto upload(MultipartFile file, String tipo, String origem, String versao, Long previous, String email) {
        if (!java.util.Set.of("HISTORICO","REALIZADOS","OPERACIONAL").contains(tipo) || !versao.equals("v1")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Tipos suportados: HISTORICO, REALIZADOS e OPERACIONAL; esquema v1.");
        }
        if (origem.isBlank() || origem.strip().length() > 160) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Indica uma origem entre 1 e 160 caracteres.");
        }
        String original = file.getOriginalFilename();
        String filename = original == null ? "" : original.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.length() > 255 || filename.chars().anyMatch(Character::isISOControl) || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Envia um ficheiro com nome .csv válido, até 255 caracteres.");
        }
        if (file.getSize() > MAX_BYTES) { throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O ficheiro excede 10 MiB."); }
        Path stored;
        byte[] bytes;
        try {
            bytes = file.getBytes();
            stored = storage.save(bytes);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Não foi possível guardar o ficheiro original.");
        }
        try {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if(tipo.equals("HISTORICO")) { return writer.persist(parser.parse(bytes, now), origem.strip(), filename, stored, hash, previous, email, now); }
            return outcomeWriter.persist(outcomes.parse(bytes,tipo,now),tipo,origem.strip(),filename,stored,hash,previous,email,now);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } catch (RuntimeException exception) {
            try { storage.discard(stored); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }
}
