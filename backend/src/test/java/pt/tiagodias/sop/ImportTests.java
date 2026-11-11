package pt.tiagodias.sop;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pt.tiagodias.sop.identity.*;
import pt.tiagodias.sop.catalog.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MYSQL_PASSWORD=test-only", "spring.application.name=sop-import-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportTests {
    private static final String PASSWORD = "Password-imports-123";
    private static final String HEADER = "produto;periodo;tipo;quantidade;unidade;disponivel_em\n";
    private static final Path STORAGE = temp();
    private static Path temp() {
        try { return Files.createTempDirectory("sop-import-tests-"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) { registry.add("sop.import.storage-path", STORAGE::toString); }
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private ProdutoService products;
    @Autowired private JdbcTemplate jdbc;
    private Browser admin;
    private String adminId;

    @BeforeAll
    void fixtures() throws Exception {
        adminId = users.createFirstAdmin("Admin", "admin@imports.test", PASSWORD).id();
        for (Papel role : List.of(Papel.PLANEADOR, Papel.COMERCIAL, Papel.APROVADOR, Papel.CONSUMIDOR)) {
            users.create(role.name(), role.name().toLowerCase(Locale.ROOT) + "@imports.test", PASSWORD, Set.of(role));
        }
        products.create(new ProdutoController.CreateProduto("SKU-IMPORT", "Monitor", "un"));
        var inactive = products.create(new ProdutoController.CreateProduto("SKU-INATIVO", "Inativo", "un"));
        jdbc.update("UPDATE produto SET ativo=false WHERE id=?", Long.valueOf(inactive.id()));
        admin = login("admin@imports.test");
    }

    @AfterAll
    void cleanup() throws Exception {
        try (var files = Files.walk(STORAGE)) {
            for (var path : files.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        }
    }

    private final class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception { token = json.readTree(get("/api/v1/auth/csrf").body()).get("token").asText(); }
        HttpResponse<String> upload(byte[] bytes, boolean csrf, String previous, String filename) throws Exception {
            String boundary = "sop" + UUID.randomUUID();
            String meta = previous == null ? "" : "--" + boundary + "\r\nContent-Disposition: form-data; name=\"substituiLoteId\"\r\n\r\n" + previous + "\r\n";
            byte[] prefix = (meta + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"ficheiro\"; filename=\"" + filename + "\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix), HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofByteArray(suffix));
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/lotes"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body);
            if (csrf) { builder.header("X-CSRF-TOKEN", token); }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> upload(String content) throws Exception { return upload(content.getBytes(StandardCharsets.UTF_8), true, null, "historico.csv"); }
    }

    private Browser login(String email) throws Exception {
        var browser = new Browser(); browser.csrf();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", browser.token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).build();
        assertThat(browser.client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        browser.csrf(); return browser;
    }

    private String row(String product, String month, String type, String quantity, String unit) {
        return product + ";" + month + ";" + type + ";" + quantity + ";" + unit + ";2025-05-01T00:00:00Z\n";
    }
    private String valid() { return HEADER + row("SKU-IMPORT", "2025-01", "VENDAS", "10", "un"); }
    private String lotId(HttpResponse<String> response) throws Exception {
        var node = json.readTree(response.body());
        return node.has("loteId") ? node.get("loteId").asText() : node.get("id").asText();
    }

    @Test
    void filtersAcrossPagesWithLiteralSearchAndValidatedMonthRange() throws Exception {
        var content=new StringBuilder(HEADER);
        for(int i=0;i<24;i++) content.append(row("SKU-IMPORT",java.time.YearMonth.of(2023,1).plusMonths(i).toString(),"VENDAS",Integer.toString(100+i),"un"));
        String id=lotId(admin.upload(content.toString()));
        var byId=json.readTree(admin.get("/api/v1/lotes?busca=%23"+id).body());
        assertThat(byId.get("totalElements").asInt()).isEqualTo(1);
        assertThat(byId.get("items").get(0).get("id").asText()).isEqualTo(id);
        String path="/api/v1/lotes/"+id+"/observacoes";
        var filtered=json.readTree(admin.get(path+"?busca=Monitor&page=1&size=20").body());
        assertThat(filtered.get("totalElements").asInt()).isEqualTo(24);
        assertThat(filtered.get("items").size()).isEqualTo(4);
        var month=json.readTree(admin.get(path+"?busca=SKU-IMPORT&desde=2023-12&ate=2023-12").body());
        assertThat(month.get("totalElements").asInt()).isEqualTo(1);
        assertThat(month.get("items").get(0).get("periodo").asText()).isEqualTo("2023-12");
        assertThat(json.readTree(admin.get(path+"?busca=%25").body()).get("totalElements").asInt()).isZero();
        assertThat(admin.get(path+"?desde=2024-13").statusCode()).isEqualTo(422);
        assertThat(admin.get(path+"?desde=2024-05&ate=2023-01").statusCode()).isEqualTo(422);
        assertThat(json.readTree(admin.get(path).body()).get("totalElements").asInt()).isEqualTo(24);
    }

    @Test
    void importsQuotedCsvPreservesZerosGapsProvenanceAndOriginal() throws Exception {
        String content = "\uFEFF" + HEADER + row("\"SKU-IMPORT\"", "2025-01", "VENDAS", "10.25", "un")
                + row("SKU-IMPORT", "2025-02", "VENDAS", "0", "un") + row("SKU-IMPORT", "2025-04", "VENDAS", "12", "un");
        var response = admin.upload(content);
        assertThat(response.statusCode()).isEqualTo(201);
        var lot = json.readTree(response.body()); String id = lot.get("id").asText();
        assertThat(lot.get("estado").asText()).isEqualTo("VALIDADO");
        assertThat(lot.get("totalLinhas").asInt()).isEqualTo(3);
        assertThat(lot.get("importadoPor").asText()).isEqualTo(adminId);
        assertThat(response.body()).doesNotContain("localizacao", STORAGE.toString());
        assertThat(lot.get("sha256").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))));
        String path = jdbc.queryForObject("SELECT localizacao_ficheiro FROM lote WHERE id=?", String.class, Long.valueOf(id));
        assertThat(Files.readString(Path.of(path))).isEqualTo(content);
        var observations = json.readTree(admin.get("/api/v1/lotes/" + id + "/observacoes").body());
        assertThat(observations.get("totalElements").asInt()).isEqualTo(3);
        assertThat(observations.get("items").get(1).get("quantidade").asText()).isEqualTo("0.0000");
        assertThat(observations.get("items").get(2).get("periodo").asText()).isEqualTo("2025-04");
        var product = jdbc.queryForObject("SELECT id FROM produto WHERE codigo='SKU-IMPORT'", Long.class);
        assertThatThrownBy(() -> products.update(product, new ProdutoController.UpdateProduto(null, null, "kg", null, 0L)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void rejectsDuplicateAndPublishesNoPartialData() throws Exception {
        var response = admin.upload(valid() + row("SKU-IMPORT", "2025-01", "VENDAS", "20", "un"));
        assertThat(response.statusCode()).isEqualTo(422); String id = lotId(response);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observacao WHERE lote_id=?", Integer.class, Long.valueOf(id))).isZero();
        var errors = json.readTree(admin.get("/api/v1/lotes/" + id + "/erros").body());
        assertThat(errors.get("items").get(0).get("codigo").asText()).isEqualTo("CHAVE_DUPLICADA");
        assertThat(errors.get("items").get(0).get("numeroLinha").asInt()).isEqualTo(3);
        assertThat(admin.get("/api/v1/lotes/" + id + "/observacoes").statusCode()).isEqualTo(409);
        assertThat(json.readTree(admin.get("/api/v1/lotes/" + id).body()).get("estado").asText()).isEqualTo("REJEITADO");
    }

    @Test
    void diagnosesInvalidValuesAndCsvWithoutPartialData() throws Exception {
        var cases = new LinkedHashMap<String, String>();
        cases.put("PRODUTO_DESCONHECIDO", HEADER + row("NAO-EXISTE", "2025-01", "VENDAS", "10", "un"));
        cases.put("PRODUTO_INATIVO", HEADER + row("SKU-INATIVO", "2025-01", "VENDAS", "10", "un"));
        cases.put("UNIDADE_INCOMPATIVEL", HEADER + row("SKU-IMPORT", "2025-01", "VENDAS", "10", "kg"));
        cases.put("QUANTIDADE_INVALIDA", HEADER + row("SKU-IMPORT", "2025-01", "VENDAS", "-1", "un"));
        cases.put("PERIODO_INVALIDO", HEADER + row("SKU-IMPORT", "2025-13", "VENDAS", "10", "un"));
        cases.put("TIPO_INVALIDO", HEADER + row("SKU-IMPORT", "2025-01", "OUTRO", "10", "un"));
        cases.put("DISPONIBILIDADE_INVALIDA", valid().replace("2025-05-01T00:00:00Z", "2999-05-01T00:00:00Z"));
        cases.put("CABECALHO_INVALIDO", "produto;quantidade\nSKU-IMPORT;1\n");
        cases.put("CSV_INVALIDO", HEADER + "\"aspas sem fecho");
        cases.put("SEM_OBSERVACOES", HEADER);
        for (var entry : cases.entrySet()) {
            var response = admin.upload(entry.getValue());
            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(422);
            String id = lotId(response);
            assertThat(admin.get("/api/v1/lotes/" + id + "/erros").body()).contains(entry.getKey());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observacao WHERE lote_id=?", Integer.class, Long.valueOf(id))).isZero();
        }
        var invalidUtf8 = admin.upload(new byte[]{(byte) 0xc3, 0x28}, true, null, "utf.csv");
        assertThat(invalidUtf8.statusCode()).isEqualTo(422);
        assertThat(admin.get("/api/v1/lotes/" + lotId(invalidUtf8) + "/erros").body()).contains("UTF8_INVALIDO");
    }

    @Test
    void enforcesRolesCsrfAndPagination() throws Exception {
        assertThat(new Browser().get("/api/v1/lotes").statusCode()).isEqualTo(401);
        assertThat(admin.upload(valid().getBytes(StandardCharsets.UTF_8), false, null, "h.csv").statusCode()).isEqualTo(403);
        for (String role : List.of("comercial", "aprovador")) {
            var browser = login(role + "@imports.test");
            assertThat(browser.upload(valid()).statusCode()).isEqualTo(403);
            assertThat(browser.get("/api/v1/lotes").statusCode()).isEqualTo(200);
        }
        var consumer = login("consumidor@imports.test");
        assertThat(consumer.get("/api/v1/lotes").statusCode()).isEqualTo(403);
        var planner = login("planeador@imports.test");
        var response = planner.upload(valid());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json.readTree(response.body()).get("importadoPor").asText()).isNotEqualTo(adminId);
        assertThat(admin.get("/api/v1/lotes?size=201").statusCode()).isEqualTo(422);
        assertThat(admin.get("/api/v1/lotes/9223372036854775807").statusCode()).isEqualTo(404);
    }

    @Test
    void correctionsCreateNewSnapshotAndPreservePreviousLot() throws Exception {
        var original = admin.upload(valid()); String id = lotId(original);
        var correction = admin.upload(valid().replace(";10;un", ";12;un").getBytes(StandardCharsets.UTF_8), true, id, "corrigido.csv");
        assertThat(correction.statusCode()).isEqualTo(201);
        var dto = json.readTree(correction.body());
        assertThat(dto.get("substituiLoteId").asText()).isEqualTo(id);
        assertThat(dto.get("id").asText()).isNotEqualTo(id);
        assertThat(admin.get("/api/v1/lotes/" + id + "/observacoes").body()).contains("10.0000");
        assertThat(admin.get("/api/v1/lotes/" + dto.get("id").asText() + "/observacoes").body()).contains("12.0000");
    }

    @Test
    void rejectsOversizedFileAndNonCsvExtension() throws Exception {
        assertThat(admin.upload(new byte[10 * 1024 * 1024 + 1], true, null, "grande.csv").statusCode()).isEqualTo(413);
        assertThat(admin.upload(valid().getBytes(StandardCharsets.UTF_8), true, null, "historico.exe").statusCode()).isEqualTo(415);
    }

    @Test
    void rollsBackAllRowsAndDiscardsOriginalOnDatabaseFailure() throws Exception {
        long lots = jdbc.queryForObject("SELECT COUNT(*) FROM lote", Long.class);
        long observations = jdbc.queryForObject("SELECT COUNT(*) FROM observacao", Long.class);
        long files;
        try (var paths = Files.list(STORAGE)) { files = paths.count(); }
        jdbc.execute("ALTER TABLE observacao ADD CONSTRAINT test_fail_import CHECK (quantidade <> 13)");
        try {
            var response = admin.upload(valid() + row("SKU-IMPORT", "2025-02", "VENDAS", "13", "un"));
            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        } finally { jdbc.execute("ALTER TABLE observacao DROP CHECK test_fail_import"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lote", Long.class)).isEqualTo(lots);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observacao", Long.class)).isEqualTo(observations);
        try (var paths = Files.list(STORAGE)) { assertThat(paths.count()).isEqualTo(files); }
    }
}
