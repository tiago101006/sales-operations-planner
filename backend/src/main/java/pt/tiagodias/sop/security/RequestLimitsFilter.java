package pt.tiagodias.sop.security;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after authorization, before reading JSON or starting expensive work. */
final class RequestLimitsFilter extends OncePerRequestFilter {
    static final int MAX_JSON_BYTES = 64 * 1024;
    private static final Set<String> EXPENSIVE_POSTS = Set.of(
            "/api/v1/lotes", "/api/v1/backtests", "/api/v1/execucoes-previsao", "/api/v1/avaliacoes");
    private final Semaphore operations = new Semaphore(2);
    private final Set<String> activeAccounts = ConcurrentHashMap.newKeySet();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest bounded = request;
        String contentType = request.getContentType();
        boolean json;
        try {
            MediaType media = contentType == null ? null : MediaType.parseMediaType(contentType);
            json = media != null && media.getType().equalsIgnoreCase("application")
                    && (media.getSubtype().equalsIgnoreCase("json") || media.getSubtype().toLowerCase(java.util.Locale.ROOT).endsWith("+json"));
        } catch (org.springframework.http.InvalidMediaTypeException invalid) {
            SecurityErrors.write(response, 415, "INVALID_CONTENT_TYPE", "Tipo de conteúdo inválido.");
            return;
        }
        if (json) {
            if (request.getContentLengthLong() > MAX_JSON_BYTES) {
                tooLarge(response);
                return;
            }
            byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
            if (body.length > MAX_JSON_BYTES) {
                tooLarge(response);
                return;
            }
            bounded = new JsonRequest(request, body);
        }
        if (!request.getMethod().equals("POST") || !EXPENSIVE_POSTS.contains(request.getServletPath())) {
            chain.doFilter(bounded, response);
            return;
        }
        String account = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!operations.tryAcquire()) {
            busy(response);
            return;
        }
        if (!activeAccounts.add(account)) {
            operations.release();
            busy(response);
            return;
        }
        try {
            chain.doFilter(bounded, response);
        } finally {
            activeAccounts.remove(account);
            operations.release();
        }
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        SecurityErrors.write(response, 413, "JSON_TOO_LARGE", "O pedido JSON excede o limite de 64 KiB.");
    }

    private static void busy(HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", "5");
        SecurityErrors.write(response, 429, "OPERATION_LIMIT", "Já existem operações em curso. Aguarda a sua conclusão e consulta os resultados antes de repetir.");
    }

    private static final class JsonRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        JsonRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Only synchronous JSON requests are supported."); }
            };
        }

        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    }
}
