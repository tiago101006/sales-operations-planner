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

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "MYSQL_PASSWORD=test-only")
class SalesOperationsPlannerApplicationTests {

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void startsWithRealMysqlSchemaAndExposesHealthWithoutDatabaseDetails() throws Exception {
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
				Integer.class)).isEqualTo(21);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM utilizador", Integer.class)).isZero();

		var client = HttpClient.newHttpClient();
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
				.GET().build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "password", "jdbc:");
	}

}
