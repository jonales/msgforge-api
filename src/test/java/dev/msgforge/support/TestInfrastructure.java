package dev.msgforge.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres e Redis para os testes de integração, nesta ordem de preferência:
 * <ol>
 *   <li>serviços já rodando, se IT_DATABASE_URL estiver definida (útil sem Docker, como nas
 *       sessões em nuvem): IT_DATABASE_URL, IT_DATABASE_USER, IT_DATABASE_PASSWORD,
 *       IT_REDIS_HOST, IT_REDIS_PORT;</li>
 *   <li>Testcontainers, se houver Docker (CI);</li>
 *   <li>nenhum: os testes de integração são desabilitados.</li>
 * </ol>
 */
record TestInfrastructure(String jdbcUrl, String user, String password, String redisHost, int redisPort) {

    static final TestInfrastructure INSTANCE = start();

    private static TestInfrastructure start() {
        var url = System.getenv("IT_DATABASE_URL");
        if (url != null && !url.isBlank()) {
            return new TestInfrastructure(url,
                    env("IT_DATABASE_USER", "msgforge"), env("IT_DATABASE_PASSWORD", "msgforge"),
                    env("IT_REDIS_HOST", "localhost"), Integer.parseInt(env("IT_REDIS_PORT", "6379")));
        }
        if (!dockerAvailable()) {
            return null;
        }
        var postgres = new PostgreSQLContainer("postgres:17-alpine");
        var redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        postgres.start();
        redis.start();
        return new TestInfrastructure(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                redis.getHost(), redis.getMappedPort(6379));
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
