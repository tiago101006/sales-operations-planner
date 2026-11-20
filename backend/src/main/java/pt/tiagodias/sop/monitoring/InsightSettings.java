package pt.tiagodias.sop.monitoring;

import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record InsightSettings(boolean enabled, URI url, String model, int timeoutSeconds) {
    public InsightSettings(@Value("${sop.ai.enabled:false}") boolean enabled,
                           @Value("${sop.ai.url:http://ollama:11434}") URI url,
                           @Value("${sop.ai.model:qwen3.5:4b-q4_K_M}") String model,
                           @Value("${sop.ai.timeout-seconds:600}") int timeoutSeconds) {
        if (!"http".equals(url.getScheme()) || !Set.of("ollama", "localhost", "127.0.0.1", "host.docker.internal").contains(url.getHost())
                || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                || !(url.getPath().isEmpty() || url.getPath().equals("/"))) {
            throw new IllegalArgumentException("A análise local exige um endereço HTTP local do Ollama.");
        }
        if (model == null || !model.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,99}") || model.contains("cloud")) {
            throw new IllegalArgumentException("Configura um modelo local válido para a análise.");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 900) throw new IllegalArgumentException("Tempo limite da análise inválido.");
        this.enabled = enabled; this.url = url; this.model = model; this.timeoutSeconds = timeoutSeconds;
    }
}
