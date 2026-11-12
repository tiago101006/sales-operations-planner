package pt.tiagodias.sop.security;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

final class ProfilePhoto {
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_INPUT_SIDE = 4096;
    private static final int OUTPUT_SIDE = 256;
    private static final Set<String> FORMATS = Set.of("jpeg", "png");

    private ProfilePhoto() {}

    static byte[] normalize(MultipartFile file) {
        if (file.isEmpty()) {
            throw invalid("Escolhe uma imagem JPEG ou PNG.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "A foto excede 2 MiB.");
        }
        try (var source = file.getInputStream(); var input = new MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("Escolhe uma imagem JPEG ou PNG.");
            var reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw invalid("Escolhe uma imagem JPEG ou PNG.");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_INPUT_SIDE || height > MAX_INPUT_SIDE) {
                    throw invalid("A foto deve ter até 4096 píxeis de largura e de altura.");
                }
                // Limit decoded raster size and encode only pixels, without original metadata.
                var params = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / (2 * OUTPUT_SIDE));
                params.setSourceSubsampling(sample, sample, 0, 0);
                var image = reader.read(0, params);
                return jpeg(scale(image, width, height));
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException invalidImage) {
            throw invalid("Não foi possível ler a imagem. Escolhe um JPEG ou PNG válido.");
        }
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        double ratio = Math.min(1.0, (double) OUTPUT_SIDE / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        var target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        var graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(image, "JPEG", stream)) {
                throw new IllegalStateException("O codificador JPEG não está disponível.");
            }
            stream.flush();
        }
        return bytes.toByteArray();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
