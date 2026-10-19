package br.com.ada.checkout;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Versao v2 (release B): le estado_saga e grava as DUAS colunas (escrita dupla),
 * para que a v1.5 continue funcionando durante o canary e depois de um rollback.
 */
@Repository
public class PedidoRepository {

    private final JdbcTemplate jdbc;

    public PedidoRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void criar(long id, long valorCentavos) {
        jdbc.update("INSERT INTO pedido (id, valor_centavos, status_pagamento, estado_saga) VALUES (?, ?, ?, ?)",
                id, valorCentavos, EstadoSaga.PENDENTE.legado(), EstadoSaga.PENDENTE.name());
    }

    public Optional<EstadoSaga> estado(long id) {
        return jdbc.query("SELECT estado_saga FROM pedido WHERE id = ?",
                rs -> rs.next() ? Optional.of(EstadoSaga.valueOf(rs.getString(1))) : Optional.empty(), id);
    }

    /**
     * Transicao condicional: so muda se o estado atual for o esperado.
     * Evento velho ou repetido nao altera nada (retorna false).
     */
    public boolean transicionar(long id, EstadoSaga esperado, EstadoSaga novo) {
        int linhas = jdbc.update(
                "UPDATE pedido SET estado_saga = ?, status_pagamento = ? WHERE id = ? AND estado_saga = ?",
                novo.name(), novo.legado(), id, esperado.name());
        return linhas == 1;
    }
}
