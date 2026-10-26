package br.com.ada.checkout;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Simula a versao estavel v1.5 (release A): le status_pagamento, grava o nome canonico em estado_saga e o token antigo so em status_pagamento.
 * Usada nos testes de coexistencia e de rollback; nao e um bean do Spring.
 */
public class PedidoRepositoryLegado {

    private final JdbcTemplate jdbc;

    public PedidoRepositoryLegado(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String statusPagamento(long id) {
        return jdbc.queryForObject("SELECT status_pagamento FROM pedido WHERE id = ?", String.class, id);
    }

    public void confirmar(long id) {
        jdbc.update("UPDATE pedido SET status_pagamento = 'CONFIRMADO', estado_saga = 'CONCLUIDO' "
                + "WHERE id = ? AND status_pagamento = 'PENDENTE'", id);
    }
}
