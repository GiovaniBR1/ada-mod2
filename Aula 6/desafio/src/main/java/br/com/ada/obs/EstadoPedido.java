package br.com.ada.obs;

/** Estados do pedido. Cancelar sem cobranca e cancelar com estorno sao estados diferentes. */
public enum EstadoPedido {
    PENDENTE(false),
    ESTOQUE_EM_VERIFICACAO(false),
    AGUARDANDO_PAGAMENTO(false),
    PAGAMENTO_EM_VERIFICACAO(false),
    /** Capturado, confirmacao da reserva ainda no prazo. Na metrica entra como ok. */
    PAGO(false),
    CONFIRMACAO_EM_VERIFICACAO(false),
    /** Estorno pedido, ainda dentro das cinco tentativas. Na metrica entra como estornado. */
    ESTORNANDO(false),
    ESTORNO_EM_REVISAO(false),
    /** Prazo de revisao esgotado. O job continua consultando o PSP uma vez por dia. */
    ESTORNO_FALHOU(false),
    CONCLUIDO(true),
    CANCELADO_SEM_COBRANCA(true),
    CANCELADO_COM_ESTORNO(true);

    private final boolean terminalParaCliente;

    EstadoPedido(boolean terminalParaCliente) {
        this.terminalParaCliente = terminalParaCliente;
    }

    /** O cliente ja recebeu um desfecho. ESTORNO_FALHOU ainda espera o job diario. */
    public boolean terminalParaCliente() {
        return terminalParaCliente;
    }

    public boolean emRevisao() {
        return this == ESTOQUE_EM_VERIFICACAO
                || this == PAGAMENTO_EM_VERIFICACAO
                || this == CONFIRMACAO_EM_VERIFICACAO
                || this == ESTORNO_EM_REVISAO
                || this == ESTORNO_FALHOU;
    }
}
