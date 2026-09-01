package com.shanshui.apmserver;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementSchemaIntegrationTests {

    @Test
    void migratesManagementAndSessionTablesOnPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "未检测到 Docker，跳过 PostgreSQL schema 集成测试");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            verifyNonEmptyLegacyApplicationMigrationGuard(postgres);
            migrateAll(postgres);
            validateJpaSchema(postgres);
            verifyApplicationIdentitySchema(postgres);
        }
    }

    private void migrateAll(PostgreSQLContainer<?> postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private void verifyNonEmptyLegacyApplicationMigrationGuard(PostgreSQLContainer<?> postgres) throws Exception {
        String schema = "migration_guard";
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("insert into " + schema + ".apm_user "
                    + "(id, email_normalized, display_name, password_hash, status, created_at, updated_at) values "
                    + "('00000000-0000-0000-0000-000000000001', 'guard@example.com', 'Guard', 'hash', 'ACTIVE', now(), now())");
            statement.execute("insert into " + schema + ".project "
                    + "(project_id, name, created_by, created_at, updated_at) values "
                    + "('guard-project', 'Guard', '00000000-0000-0000-0000-000000000001', now(), now())");
        }
        assertThrows(FlywayException.class, () -> Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate());
    }

    private void verifyApplicationIdentitySchema(PostgreSQLContainer<?> postgres) throws Exception {
        String url = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String password = postgres.getPassword();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from information_schema.tables "
                     + "where table_schema = 'public' and table_name in ('apm_user', 'apm_app', "
                     + "'app_member', 'app_ingest_credential', 'spring_session', 'spring_session_attributes')")) {
            assertTrue(result.next());
            assertEquals(6, result.getInt(1));
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from information_schema.tables "
                     + "where table_schema = 'public' and table_name in ('project', 'project_member', 'project_ingest_credential')")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from pg_constraint c "
                     + "join pg_namespace n on n.oid = c.connamespace where n.nspname = 'public' and conname in "
                     + "('apm_user_status_ck', 'apm_app_package_name_uk', 'apm_app_package_name_ck', "
                     + "'apm_app_name_not_blank_ck', 'apm_app_created_updated_ck', 'app_member_role_ck', "
                     + "'app_ingest_credential_key_digest_uk', 'app_ingest_credential_key_digest_length_ck', "
                     + "'app_ingest_credential_key_ciphertext_length_ck', "
                     + "'app_ingest_credential_key_nonce_length_ck')")) {
            assertTrue(result.next());
            assertEquals(10, result.getInt(1));
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from information_schema.columns "
                     + "where table_schema = 'public' and table_name = 'app_ingest_credential' and column_name in "
                     + "('app_id', 'key_digest', 'key_ciphertext', 'key_nonce', 'created_at')")) {
            assertTrue(result.next());
            assertEquals(5, result.getInt(1));
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("select data_type from information_schema.columns "
                     + "where table_schema = 'public' and table_name = 'apm_app' and column_name = 'app_id'")) {
            assertTrue(result.next());
            assertEquals("uuid", result.getString(1));
        }

        UUID userId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID secondAppId = UUID.randomUUID();
        execute(url, user, password, "insert into apm_user "
                + "(id, email_normalized, display_name, password_hash, status, created_at, updated_at) values ('"
                + userId + "', 'schema@example.com', 'Schema', 'hash', 'ACTIVE', now(), now())");
        insertApp(url, user, password, appId, "com.example.schema");
        insertApp(url, user, password, secondAppId, "com.example.schema.two");
        execute(url, user, password, "insert into app_member (app_id, user_id, role, created_at) values ('"
                + appId + "', '" + userId + "', 'OWNER', now())");
        execute(url, user, password, "insert into app_ingest_credential "
                + "(app_id, key_digest, key_ciphertext, key_nonce, created_at) values ('" + appId
                + "', decode(repeat('ab', 32), 'hex'), decode(repeat('cd', 16), 'hex'), "
                + "decode(repeat('ef', 12), 'hex'), now())");

        assertThrows(SQLException.class, () -> insertApp(url, user, password, UUID.randomUUID(), "com.example.schema"));
        assertThrows(SQLException.class, () -> insertApp(url, user, password, UUID.randomUUID(), "com.Example.invalid"));
        assertThrows(SQLException.class, () -> execute(url, user, password, "insert into app_ingest_credential "
                + "(app_id, key_digest, key_ciphertext, key_nonce, created_at) values ('" + secondAppId
                + "', decode(repeat('ab', 32), 'hex'), decode(repeat('cd', 16), 'hex'), "
                + "decode(repeat('ef', 12), 'hex'), now())"));

        execute(url, user, password, "delete from apm_app where app_id = '" + appId + "'");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("select (select count(*) from app_member where app_id = '" + appId
                     + "') + (select count(*) from app_ingest_credential where app_id = '" + appId + "')")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
    }

    private void insertApp(String url, String user, String password, UUID appId, String packageName) throws SQLException {
        execute(url, user, password, "insert into apm_app "
                + "(app_id, package_name, name, created_by, created_at, updated_at) values ('"
                + appId + "', '" + packageName + "', '" + packageName
                + "', (select id from apm_user where email_normalized = 'schema@example.com'), now(), now())");
    }

    private void execute(String url, String user, String password, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void validateJpaSchema(PostgreSQLContainer<?> postgres) {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var entityManagerFactory = new LocalContainerEntityManagerFactoryBean();
        entityManagerFactory.setDataSource(dataSource);
        entityManagerFactory.setPackagesToScan("com.shanshui.apmserver.domain");
        entityManagerFactory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        var properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        entityManagerFactory.setJpaProperties(properties);
        entityManagerFactory.afterPropertiesSet();
        if (entityManagerFactory.getObject() != null) {
            entityManagerFactory.getObject().close();
        }
    }
}
