package pt.tiagodias.sop;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayAdoptionTests {

    @Test
    void requiresExplicitBaselineAndPreservesExistingData() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4")) {
            mysql.start();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__schema_inicial.sql"));
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO produto(codigo,nome,unidade) VALUES ('TESTE','Produto existente','un')");
                }
                var flyway = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                        .baselineVersion("1").baselineOnMigrate(false).cleanDisabled(true).load();
                assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
                flyway.baseline();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT nome FROM produto WHERE codigo='TESTE'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("Produto existente");
                }
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT versao_lock FROM produto WHERE codigo='TESTE'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
            }
        }
    }
}
