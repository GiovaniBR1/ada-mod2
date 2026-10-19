package br.com.ada.checkout;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Testa as migracoes sem Spring: cada teste usa um banco H2 novo em modo PostgreSQL. */
class MigracaoExpandContractTest {

    private JdbcDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void bancoNovo() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void aplicaSoExpandEMigrateEMantemColunaAntiga() {
        List<String> versoes = jdbc.queryForList(
                "SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" AND \"version\" IS NOT NULL ORDER BY \"installed_rank\"", String.class);
        assertThat(versoes).containsExactly("1", "2", "3", "4");
        // verificacao negativa: o contract (V5) NAO pode ter rodado; a coluna antiga existe
        Integer colunas = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'PEDIDO' AND COLUMN_NAME IN ('STATUS_PAGAMENTO', 'ESTADO_SAGA')", Integer.class);
        assertThat(colunas).isEqualTo(2);
    }

    @Test
    void backfillSeparaCanceladoSemCobrancaDeCanceladoComEstorno() {
        assertThat(estado(2)).isEqualTo("CANCELADO_SEM_COBRANCA");
        assertThat(estado(3)).isEqualTo("CANCELADO_COM_ESTORNO");
        assertThat(estado(4)).isEqualTo("ESTORNO_EM_REVISAO");
        assertThat(estado(1)).isEqualTo("CONFIRMADO");
        // verificacoes negativas
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pedido WHERE estado_saga IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pedido p WHERE p.estado_saga = 'CANCELADO_SEM_COBRANCA' "
                + "AND EXISTS (SELECT 1 FROM pagamento g WHERE g.pedido_id = p.id AND g.capturado)", Integer.class))
                .as("cancelado sem cobranca nao pode ter pagamento capturado").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pedido WHERE estado_saga = 'CONFIRMADO' "
                + "AND status_pagamento <> 'CONFIRMADO'", Integer.class)).as("colunas divergentes").isZero();
    }

    @Test
    void backfillEIdempotente() throws Exception {
        String sql = new String(getClass().getResourceAsStream("/db/migration/V4__backfill_estado_saga.sql").readAllBytes());
        int alteradas = jdbc.update(sql.replaceAll("(?m)^--.*$", ""));
        assertThat(alteradas).as("segunda execucao do backfill nao muda nada").isZero();
    }

    @Test
    void v1EV2CoexistemERollbackNaoQuebra() {
        PedidoRepository v2 = new PedidoRepository(jdbc);
        PedidoRepositoryLegado v15 = new PedidoRepositoryLegado(jdbc);

        v2.criar(100, 2500);                                   // criado pela v2 (canary)
        assertThat(v15.statusPagamento(100)).isEqualTo("PENDENTE"); // a v1.5 le sem erro de SQL
        v15.confirmar(100);                                    // rollback: a v1.5 avanca o pedido da v2
        assertThat(v2.estado(100)).contains(EstadoSaga.CONFIRMADO);
        assertThat(v15.statusPagamento(100)).isEqualTo("CONFIRMADO");
    }

    @Test
    void transicaoCondicionalIgnoraEventoVelhoOuRepetido() {
        PedidoRepository v2 = new PedidoRepository(jdbc);
        v2.criar(200, 1000);
        assertThat(v2.transicionar(200, EstadoSaga.PENDENTE, EstadoSaga.CANCELADO_SEM_COBRANCA)).isTrue();
        // evento "pagamento confirmado" chega atrasado: nao pode ressuscitar o pedido
        assertThat(v2.transicionar(200, EstadoSaga.PENDENTE, EstadoSaga.CONFIRMADO)).isFalse();
        // repeticao do mesmo cancelamento: exatamente uma transicao aplicada
        assertThat(v2.transicionar(200, EstadoSaga.PENDENTE, EstadoSaga.CANCELADO_SEM_COBRANCA)).isFalse();
        assertThat(v2.estado(200)).contains(EstadoSaga.CANCELADO_SEM_COBRANCA);
        assertThat(jdbc.queryForObject("SELECT status_pagamento FROM pedido WHERE id = 200", String.class)).isEqualTo("CANCELADO");
    }

    @Test
    void checksumBarraMigracaoAplicadaQueFoiAlterada(@TempDir Path dir) throws Exception {
        for (String f : List.of("V1__init_pedido.sql", "V2__seed_pedidos.sql")) {
            Files.write(dir.resolve(f), getClass().getResourceAsStream("/db/migration/" + f).readAllBytes());
        }
        JdbcDataSource outro = new JdbcDataSource();
        outro.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        outro.setUser("sa");
        Flyway fw = Flyway.configure().dataSource(outro).locations("filesystem:" + dir).load();
        fw.migrate();
        Files.writeString(dir.resolve("V1__init_pedido.sql"), "\n-- alteracao indevida\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(fw::validate).isInstanceOf(FlywayException.class).hasMessageContaining("checksum mismatch");
    }

    @Test
    void guardrailNenhumaMigracaoDestrutivaEmDbMigration() throws Exception {
        Path pasta = Path.of("src/main/resources/db/migration");
        try (Stream<Path> arquivos = Files.list(pasta)) {
            List<String> destrutivas = arquivos
                    .filter(p -> {
                        try {
                            String sql = Files.readString(p).replaceAll("(?m)--.*$", "").toUpperCase();
                            return sql.contains("DROP ") || sql.contains("RENAME ") || sql.matches("(?s).*ALTER COLUMN .* TYPE.*");
                        } catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .map(p -> p.getFileName().toString()).toList();
            assertThat(destrutivas).as("DROP/RENAME/TYPE so em db/contract, numa release futura").isEmpty();
        }
    }

    private String estado(long id) {
        return jdbc.queryForObject("SELECT estado_saga FROM pedido WHERE id = ?", String.class, id);
    }
}
