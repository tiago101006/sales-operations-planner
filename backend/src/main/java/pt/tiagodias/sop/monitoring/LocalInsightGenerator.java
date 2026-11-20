package pt.tiagodias.sop.monitoring;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static pt.tiagodias.sop.monitoring.InsightDtos.*;

/** Calls only the configured local Ollama server; it never accepts a URL or prompt from a browser. */
@Component
public class LocalInsightGenerator {
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private final InsightSettings settings;
    private final JsonMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public LocalInsightGenerator(InsightSettings settings, JsonMapper json) { this.settings=settings; this.json=json; }

    public List<String> generate(String evidence,List<GroundedInsightReport.Recommendation> catalog) {
        var body = Map.of("model", settings.model(), "system", InsightPrompt.SYSTEM,
                "prompt", "Ordena todos os IDs das recomendações elegíveis, sem omissões, com o esquema JSON pedido. DADOS:\n" + evidence,
                "format", InsightPrompt.schema(catalog), "stream", false, "keep_alive", 0,"truncate",false,"shift",false,"think",false,
                "options", Map.of("temperature", 0.1, "num_ctx", 16384, "num_predict", 1200,"num_thread",3));
        var request = HttpRequest.newBuilder(settings.url().resolve("/api/generate"))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var subscriber = new LimitedBody();
        var future = client.sendAsync(request, ignored -> subscriber);
        try {
            var response = future.get(settings.timeoutSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() == 400) throw new InsightFailure("O modelo local recusou o pedido. Confirma o modelo configurado ou usa uma avaliação com menos produtos.");
            if (response.statusCode() != 200) throw new InsightFailure("O modelo local não está disponível. Confirma que o Ollama e o modelo estão iniciados.");
            JsonNode result = json.readTree(response.body());
            // Reject prompts without sufficient output headroom as well as server-level context errors.
            if (!result.path("prompt_eval_count").isIntegralNumber() || result.path("prompt_eval_count").asInt()>12_000) {
                throw new InsightFailure("A avaliação excede o contexto seguro do modelo local. Usa uma janela menor; não foi publicada uma análise parcial.");
            }
            if (!result.path("done").asBoolean() || "length".equals(result.path("done_reason").asText())) {
                throw new InsightFailure("O modelo não concluiu o relatório dentro do limite. Tenta novamente ou usa uma avaliação com menos produtos.");
            }
            return validate(result.path("response").asText(),catalog);
        } catch (TimeoutException error) {
            throw new InsightFailure("A análise excedeu o tempo limite do modelo local. Tenta novamente quando o computador estiver menos ocupado.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InsightFailure("A análise local foi interrompida. Podes tentar novamente.");
        } catch (InsightFailure error) { throw error;
        } catch (Exception error) {
            throw new InsightFailure("Não foi possível obter um relatório válido do modelo local. Confirma que o Ollama está iniciado e tenta novamente.");
        } finally {
            subscriber.cancel(); future.cancel(true);
        }
    }

    List<String> validate(String response,List<GroundedInsightReport.Recommendation> catalog) {
        if (response == null || response.length() > 8_000) throw new InsightFailure("A priorização recebida ultrapassou o tamanho permitido.");
        try {
            JsonNode value = json.readTree(response);
            if(!value.isObject() || value.size()!=1 || !value.path("prioridades").isArray())throw new IllegalArgumentException();
            var ids=new ArrayList<String>();for(var id:value.get("prioridades")){if(!id.isTextual())throw new IllegalArgumentException();ids.add(id.asText());}
            GroundedInsightReport.validateOrder(catalog,ids);
            return List.copyOf(ids);
        } catch (Exception error) {
            throw new InsightFailure("O modelo não devolveu uma priorização completa e válida. Podes tentar novamente.");
        }
    }
    public static final class InsightFailure extends RuntimeException {
        public InsightFailure(String message) { super(message); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private volatile Subscription subscription;
        public CompletionStage<String> getBody() { return body; }
        public void onSubscribe(Subscription value) { subscription=value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if ((long)bytes.size()+chunk.remaining()>MAX_RESPONSE_BYTES) {
                    cancel(); body.completeExceptionally(new IllegalStateException("Oversized model response")); return;
                }
                byte[] copy=new byte[chunk.remaining()]; chunk.get(copy); bytes.writeBytes(copy);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
        void cancel() { if (subscription!=null) subscription.cancel(); }
    }
}
