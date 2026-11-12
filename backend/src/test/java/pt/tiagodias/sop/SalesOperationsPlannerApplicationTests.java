package pt.tiagodias.sop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "MYSQL_PASSWORD=test-only")
class SalesOperationsPlannerApplicationTests {

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Flyway flyway;

	@Test
	void servesInterfaceAndAssetsWithoutExposingProtectedApis() throws Exception {
		var client=HttpClient.newHttpClient();
		for(String path:java.util.List.of("/","/index.html","/assets/app.css","/assets/app.mjs","/assets/ui.mjs","/assets/api.mjs","/assets/views.mjs","/assets/brand.svg")) {
			var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as(path).isEqualTo(200);
			assertThat(response.headers().firstValue("Content-Security-Policy").orElse("")).contains("script-src 'self'","frame-ancestors 'none'");
			if(path.endsWith(".mjs")) { assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("javascript"); }
		}
		for(String path:java.util.List.of("/api/v1/auth/me","/api/v1/planos","/api/v1/cenarios","/api/v1/avaliacoes")) {
			var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as(path).isEqualTo(401);
		}
	}

	@Test
	void rejectsChecksumMismatchAndAllowsRepeatedStartup() {
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		Integer checksum = jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='1'", Integer.class);
		try {
			jdbc.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
			assertThatThrownBy(flyway::validate).isInstanceOf(FlywayException.class);
		} finally {
			jdbc.update("UPDATE flyway_schema_history SET checksum=? WHERE version='1'", checksum);
		}
		flyway.validate();
	}

	@Test
	void startsWithRealMysqlSchemaAndExposesHealthWithoutDatabaseDetails() throws Exception {
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
				Integer.class)).isEqualTo(26);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND type='SQL' AND success=1",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM utilizador", Integer.class)).isZero();

		var client = HttpClient.newHttpClient();
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
				.GET().build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "password", "jdbc:");
	}

}
