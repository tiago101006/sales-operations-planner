package pt.tiagodias.sop;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import pt.tiagodias.sop.identity.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MYSQL_PASSWORD=test-only", "spring.application.name=sop-catalog-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogTests {
    private static final String PASSWORD = "Password-catalogo-123";
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private JdbcTemplate jdbc;
    private Browser admin;
    private String adminId;

    @BeforeAll
    void accounts() throws Exception {
        adminId = users.createFirstAdmin("Admin", "admin@catalog.test", PASSWORD).id();
        for (Papel role : List.of(Papel.PLANEADOR, Papel.COMERCIAL, Papel.APROVADOR, Papel.CONSUMIDOR)) {
            users.create(role.name(), role.name().toLowerCase(Locale.ROOT) + "@catalog.test", PASSWORD, Set.of(role));
        }
        admin = login("admin@catalog.test");
    }

    private final class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;

        HttpResponse<String> request(String method, String path, Object body, boolean csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf) { builder.header("X-CSRF-TOKEN", token); }
            if (body == null) { builder.method(method, HttpRequest.BodyPublishers.noBody()); }
            else { builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))); }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> saveWithPhoto(String method, String path, Object product, byte[] image, boolean csrf) throws Exception {
            String boundary = "foto-" + UUID.randomUUID();
            var body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"produto\"; filename=\"produto.json\"\r\n"
                            + "Content-Type: application/json\r\n\r\n" + json.writeValueAsString(product)
                            + "\r\n--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"foto\"; filename=\"produto.png\"\r\n"
                            + "Content-Type: image/png\r\n\r\n"),
                    HttpRequest.BodyPublishers.ofByteArray(image),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).method(method, body);
            if (csrf) builder.header("X-CSRF-TOKEN", token);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<byte[]> photo(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + "/foto")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        }

        void csrf() throws Exception { token = json.readTree(request("GET", "/api/v1/auth/csrf", null, false).body()).get("token").asText(); }
    }

    private Browser login(String email) throws Exception {
        var browser = new Browser();
        browser.csrf();
        assertThat(browser.request("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), true).statusCode()).isEqualTo(200);
        browser.csrf();
        return browser;
    }

    private JsonNode create(String code, String nome) throws Exception {
        var response = admin.request("POST", "/api/v1/produtos", Map.of("codigo", code, "nome", nome, "unidade", "un"), true);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).isPresent();
        return json.readTree(response.body());
    }

    private String unique() { return "T-" + UUID.randomUUID(); }

    private static byte[] png(int width, int height, Color color) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void productPhotosAreOptionalPrivateAndSavedWithTheProductVersion() throws Exception {
        String catalog = "/api/v1/produtos";
        var withoutPhoto = create(unique(), "Sem foto");
        assertThat(withoutPhoto.get("temFoto").asBoolean()).isFalse();
        assertThat(admin.photo(catalog + "/" + withoutPhoto.get("id").asText()).statusCode()).isEqualTo(404);

        String code = unique();
        var command = Map.of("codigo", code, "nome", "Com foto", "unidade", "un");
        assertThat(admin.saveWithPhoto("POST", catalog, command, new byte[]{1, 2, 3}, true).statusCode()).isEqualTo(422);
        var listing = admin.request("GET", catalog + "?q=" + code, null, false);
        assertThat(json.readTree(listing.body()).get("totalElements").asInt()).isZero();
        var image = png(480, 240, Color.RED);
        assertThat(admin.saveWithPhoto("POST", catalog, command, image, false).statusCode()).isEqualTo(403);
        var response = admin.saveWithPhoto("POST", catalog, command, image, true);
        assertThat(response.statusCode()).isEqualTo(201);
        var created = json.readTree(response.body());
        assertThat(created.get("temFoto").asBoolean()).isTrue();
        assertThat(created.get("versao").asLong()).isZero();

        String path = response.headers().firstValue("Location").orElseThrow();
        var consumer = login("consumidor@catalog.test");
        assertThat(new Browser().photo(path).statusCode()).isEqualTo(401);
        var thumbnail = consumer.photo(path);
        assertThat(thumbnail.statusCode()).isEqualTo(200);
        assertThat(thumbnail.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(thumbnail.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(ImageIO.read(new ByteArrayInputStream(thumbnail.body())).getWidth()).isEqualTo(256);
        assertThat(consumer.saveWithPhoto("PATCH", path, Map.of("versaoEsperada", 0), image, true).statusCode()).isEqualTo(403);

        var replace = Map.of("nome", "Nome revisto", "versaoEsperada", 0);
        assertThat(admin.saveWithPhoto("PATCH", path, replace, new byte[]{1, 2, 3}, true).statusCode()).isEqualTo(422);
        assertThat(json.readTree(admin.request("GET", path, null, false).body())).isEqualTo(created);
        assertThat(admin.photo(path).body()).isEqualTo(thumbnail.body());
        assertThat(admin.saveWithPhoto("PATCH", path, replace, new byte[2 * 1024 * 1024 + 1], true).statusCode()).isEqualTo(413);

        var replaced = admin.saveWithPhoto("PATCH", path, Map.of("versaoEsperada", 0), png(120, 240, Color.BLUE), true);
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replaced.body()).get("versao").asLong()).isEqualTo(1);
        assertThat(ImageIO.read(new ByteArrayInputStream(admin.photo(path).body())).getWidth()).isEqualTo(120);
        assertThat(admin.request("PATCH", path, Map.of("removerFoto", true, "versaoEsperada", 0), true).statusCode()).isEqualTo(409);
        var removed = admin.request("PATCH", path, Map.of("removerFoto", true, "versaoEsperada", 1), true);
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(removed.body()).get("temFoto").asBoolean()).isFalse();
        assertThat(json.readTree(removed.body()).get("versao").asLong()).isEqualTo(2);
        assertThat(consumer.photo(path).statusCode()).isEqualTo(404);
    }

    @Test
    void listsTeamOnlyForAdminWithoutAuthenticationSecrets() throws Exception {
        var response=admin.request("GET","/api/v1/utilizadores?busca=Administrador&size=1",null,false);
        assertThat(response.statusCode()).isEqualTo(200);
        var data=json.readTree(response.body());
        assertThat(data.get("totalElements").asInt()).isEqualTo(1);
        assertThat(data.get("items").get(0).get("email").asText()).isEqualTo("admin@catalog.test");
        assertThat(data.get("items").get(0).size()).isEqualTo(8);
        assertThat(data.get("items").get(0).get("temFoto").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("\"password\":", "password_hash", "$2");
        assertThat(new Browser().request("GET","/api/v1/utilizadores",null,false).statusCode()).isEqualTo(401);
        assertThat(login("planeador@catalog.test").request("GET","/api/v1/utilizadores",null,false).statusCode()).isEqualTo(403);
    }

    @Test
    void restrictsWritesByRoleAndRequiresCsrf() throws Exception {
        assertThat(new Browser().request("GET", "/api/v1/produtos", null, false).statusCode()).isEqualTo(401);
        var product = create(unique(), "Permissoes");
        String path = "/api/v1/produtos/" + product.get("id").asText();
        for (String role : List.of("comercial", "aprovador", "consumidor")) {
            var browser = login(role + "@catalog.test");
            assertThat(browser.request("GET", path, null, false).statusCode()).isEqualTo(200);
            assertThat(browser.request("GET", "/api/v1/produtos", null, false).statusCode()).isEqualTo(200);
            assertThat(browser.request("POST", "/api/v1/produtos", Map.of("codigo", unique(), "nome", "Recusado", "unidade", "un"), true).statusCode()).isEqualTo(403);
            assertThat(browser.request("PATCH", path, Map.of("ativo", false, "versaoEsperada", 0), true).statusCode()).isEqualTo(403);
        }
        var planner = login("planeador@catalog.test");
        assertThat(planner.request("POST", "/api/v1/produtos", Map.of("codigo", unique(), "nome", "Permitido", "unidade", "kg"), true).statusCode()).isEqualTo(201);
        assertThat(admin.request("POST", "/api/v1/produtos", Map.of("codigo", unique(), "nome", "Sem CSRF", "unidade", "un"), false).statusCode()).isEqualTo(403);
        assertThat(admin.request("DELETE", path, null, true).statusCode()).isEqualTo(400);
    }

    @Test
    void deletesUnusedProductAndPhotoOnlyForAdminWithTheCurrentVersion() throws Exception {
        String code = unique();
        var created = admin.saveWithPhoto("POST", "/api/v1/produtos",
                Map.of("codigo", code, "nome", "Eliminar com foto", "unidade", "un"), png(100, 100, Color.GREEN), true);
        assertThat(created.statusCode()).isEqualTo(201);
        String path = created.headers().firstValue("Location").orElseThrow();
        String delete = path + "?versaoEsperada=0";
        var anonymous = new Browser();
        anonymous.csrf();
        assertThat(anonymous.request("DELETE", delete, null, true).statusCode()).isEqualTo(401);
        for (String role : List.of("planeador", "comercial", "aprovador", "consumidor")) {
            assertThat(login(role + "@catalog.test").request("DELETE", delete, null, true).statusCode()).isEqualTo(403);
        }
        assertThat(admin.request("DELETE", delete, null, false).statusCode()).isEqualTo(403);
        assertThat(admin.request("DELETE", path + "?versaoEsperada=-1", null, true).statusCode()).isEqualTo(422);
        assertThat(admin.request("PATCH", path, Map.of("nome", "Nome revisto", "versaoEsperada", 0), true).statusCode()).isEqualTo(200);
        byte[] photo = admin.photo(path).body();
        assertThat(admin.request("DELETE", delete, null, true).statusCode()).isEqualTo(409);
        assertThat(admin.request("GET", path, null, false).statusCode()).isEqualTo(200);
        assertThat(admin.photo(path).body()).isEqualTo(photo);
        assertThat(admin.request("DELETE", path + "?versaoEsperada=1", null, true).statusCode()).isEqualTo(204);
        assertThat(admin.request("GET", path, null, false).statusCode()).isEqualTo(404);
        assertThat(admin.photo(path).statusCode()).isEqualTo(404);
        assertThat(admin.request("DELETE", path + "?versaoEsperada=1", null, true).statusCode()).isEqualTo(404);
        assertThat(json.readTree(admin.request("GET", "/api/v1/produtos?q=" + code, null, false).body()).get("totalElements").asInt()).isZero();

        var withoutPhoto = create(unique(), "Eliminar sem foto");
        String withoutPhotoPath = "/api/v1/produtos/" + withoutPhoto.get("id").asText();
        assertThat(admin.request("DELETE", withoutPhotoPath + "?versaoEsperada=0", null, true).statusCode()).isEqualTo(204);
    }

    @Test
    void validatesInputUniquenessAndMissingProduct() throws Exception {
        String code = unique();
        var product = create(code, "Produto");
        assertThat(product.get("versao").asInt()).isZero();
        assertThat(product.get("ativo").asBoolean()).isTrue();
        assertThat(admin.request("POST", "/api/v1/produtos", Map.of("codigo", code, "nome", "Duplicado", "unidade", "un"), true).statusCode()).isEqualTo(409);
        assertThat(admin.request("POST", "/api/v1/produtos", Map.of("codigo", " ", "nome", "", "unidade", ""), true).statusCode()).isEqualTo(422);
        assertThat(admin.request("POST", "/api/v1/produtos", Map.of("codigo", "A".repeat(65), "nome", "Excesso", "unidade", "un"), true).statusCode()).isEqualTo(422);
        assertThat(admin.request("GET", "/api/v1/produtos/9223372036854775807", null, false).statusCode()).isEqualTo(404);
    }

    @Test
    void paginatesFiltersAndEscapesSearchWildcards() throws Exception {
        String prefix = "PAG-" + UUID.randomUUID().toString().substring(0, 8);
        create(prefix + "-A", "Grupo A");
        create(prefix + "-B", "Desconto 10%");
        var c = create(prefix + "-C", "Grupo C");
        assertThat(admin.request("PATCH", "/api/v1/produtos/" + c.get("id").asText(), Map.of("ativo", false, "versaoEsperada", 0), true).statusCode()).isEqualTo(200);
        var page = json.readTree(admin.request("GET", "/api/v1/produtos?q=" + prefix + "&size=2", null, false).body());
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("codigo").asText()).isEqualTo(prefix + "-A");
        var second = json.readTree(admin.request("GET", "/api/v1/produtos?q=" + prefix + "&size=2&page=1", null, false).body());
        assertThat(second.get("items").size()).isEqualTo(1);
        var inactive = json.readTree(admin.request("GET", "/api/v1/produtos?q=" + prefix + "&ativo=false", null, false).body());
        assertThat(inactive.get("totalElements").asInt()).isEqualTo(1);
        var literal = json.readTree(admin.request("GET", "/api/v1/produtos?q=%25", null, false).body());
        assertThat(literal.get("totalElements").asInt()).isEqualTo(1);
        assertThat(admin.request("GET", "/api/v1/produtos?size=201", null, false).statusCode()).isEqualTo(422);
        assertThat(admin.request("GET", "/api/v1/produtos?page=-1", null, false).statusCode()).isEqualTo(422);
    }

    @Test
    void editsUnusedProductAndRejectsStaleVersion() throws Exception {
        var product = create(unique(), "Inicial");
        String path = "/api/v1/produtos/" + product.get("id").asText();
        assertThat(admin.request("PATCH", path, Map.of("nome", "Sem versao"), true).statusCode()).isEqualTo(422);
        assertThat(admin.request("PATCH", path, Map.of("nome", "  ", "versaoEsperada", 0), true).statusCode()).isEqualTo(422);
        var changed = admin.request("PATCH", path, Map.of("codigo", unique(), "nome", "Alterado", "unidade", "kg", "ativo", false, "versaoEsperada", 0), true);
        assertThat(changed.statusCode()).isEqualTo(200);
        var dto = json.readTree(changed.body());
        assertThat(dto.get("versao").asInt()).isEqualTo(1);
        assertThat(dto.get("ativo").asBoolean()).isFalse();
        assertThat(dto.get("unidade").asText()).isEqualTo("kg");
        assertThat(admin.request("PATCH", path, Map.of("nome", "Desatualizado", "versaoEsperada", 0), true).statusCode()).isEqualTo(409);
        assertThat(admin.request("PATCH", path, Map.of("ativo", true, "versaoEsperada", 1), true).statusCode()).isEqualTo(200);
    }

    @Test
    void freezesIdentityForEveryDirectUsageButAllowsNameAndDeactivation() throws Exception {
        for (String kind : List.of("HISTORICO", "PROMOCOES", "PREVISAO", "OPERACIONAL", "BACKTEST")) {
            var product = create(unique(), "Uso " + kind);
            long productId = product.get("id").asLong();
            String marker = UUID.randomUUID().toString();
            String lotType = Set.of("PREVISAO", "BACKTEST").contains(kind) ? "HISTORICO" : kind;
            jdbc.update("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em) VALUES (?,'VALIDADO','teste',?,'fixture',REPEAT('a',64),'v1',?,NOW())", lotType, marker, Long.valueOf(adminId));
            Long lotId = jdbc.queryForObject("SELECT id FROM lote WHERE nome_ficheiro=?", Long.class, marker);
            switch (kind) {
                case "HISTORICO" -> jdbc.update("INSERT INTO observacao(lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (?,?,'2026-10-01','VENDAS',10,NOW())", lotId, productId);
                case "PROMOCOES" -> jdbc.update("INSERT INTO promocao(lote_id,produto_id,descricao,inicio,fim,disponivel_em) VALUES (?,?,'Campanha','2026-10-01','2026-10-31',NOW())", lotId, productId);
                case "OPERACIONAL" -> jdbc.update("INSERT INTO resultado_operacional(lote_id,produto_id,periodo,stock_fecho,limite_stock,disponivel_em) VALUES (?,?,'2026-10-01',10,20,NOW())", lotId, productId);
                case "PREVISAO" -> {
                    jdbc.update("INSERT INTO execucao_previsao(lote_historico_id,tipo_serie,corte_temporal,ultimo_periodo_treino,metodo_pedido,parametros,versao_software,versao_motor,criado_por) VALUES (?,'VENDAS','2026-10-31 23:59:59','2026-10-01','MEDIA_MOVEL','{}',?,'teste',?)", lotId, marker, Long.valueOf(adminId));
                    Long execution = jdbc.queryForObject("SELECT id FROM execucao_previsao WHERE versao_software=?", Long.class, marker);
                    jdbc.update("INSERT INTO execucao_produto(execucao_id,produto_id,parametros_aplicados,estado) VALUES (?,?,'{}','PREVISTO')", execution, productId);
                }
                case "BACKTEST" -> {
                    jdbc.update("INSERT INTO backtest(lote_id,lote_sha256,tipo_serie,primeiro_corte,ultimo_corte,horizonte_meses,corte_realizados,parametros,versao_mm,versao_ets,versao_calculo,estado,criado_por) VALUES (?,REPEAT('a',64),'VENDAS','2026-10-01','2026-10-01',1,NOW(),'{}','teste','teste',?,'EM_EXECUCAO',?)", lotId, marker, Long.valueOf(adminId));
                    Long backtest = jdbc.queryForObject("SELECT id FROM backtest WHERE versao_calculo=?", Long.class, marker);
                    jdbc.update("INSERT INTO backtest_celula(backtest_id,lote_id,produto_id,corte,periodo,horizonte,estado_mm,estado_ets,parametros_mm,parametros_ets) VALUES (?,?,?,'2026-10-01','2026-11-01',1,'INSUFICIENTE','INSUFICIENTE','{}','{}')", backtest, lotId, productId);
                }
            }
            String path = "/api/v1/produtos/" + productId;
            assertThat(admin.request("PATCH", path, Map.of("codigo", unique(), "versaoEsperada", 0), true).statusCode()).isEqualTo(409);
            assertThat(admin.request("PATCH", path, Map.of("unidade", "kg", "versaoEsperada", 0), true).statusCode()).isEqualTo(409);
            assertThat(admin.request("DELETE", path + "?versaoEsperada=0", null, true).statusCode()).isEqualTo(409);
            var permitted = admin.request("PATCH", path, Map.of("nome", "Nome revisto", "ativo", false, "versaoEsperada", 0), true);
            assertThat(permitted.statusCode()).isEqualTo(200);
            assertThat(json.readTree(permitted.body()).get("codigo").asText()).isEqualTo(product.get("codigo").asText());
            assertThat(admin.request("DELETE", path + "?versaoEsperada=1", null, true).statusCode()).isEqualTo(409);
        }
    }

    @Test
    void concurrentUpdatesFromSameVersionProduceOneConflict() throws Exception {
        var product = create(unique(), "Concorrencia");
        String path = "/api/v1/produtos/" + product.get("id").asText();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> admin.request("PATCH", path, Map.of("nome", "Primeiro", "versaoEsperada", 0), true).statusCode());
            var second = executor.submit(() -> admin.request("PATCH", path, Map.of("nome", "Segundo", "versaoEsperada", 0), true).statusCode());
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); }
        var current = json.readTree(admin.request("GET", path, null, false).body());
        assertThat(current.get("versao").asInt()).isEqualTo(1);
    }
}
