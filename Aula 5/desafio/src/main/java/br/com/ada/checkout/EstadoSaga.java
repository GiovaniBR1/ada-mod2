package br.com.ada.checkout;

/** Estados da coluna nova estado_saga e o valor equivalente na coluna antiga status_pagamento. */
public enum EstadoSaga {
    PENDENTE("PENDENTE"),
    CONFIRMADO("CONFIRMADO"),
    CANCELADO_SEM_COBRANCA("CANCELADO"),
    CANCELADO_COM_ESTORNO("CANCELADO"),
    ESTORNO_EM_REVISAO("CANCELADO");

    private final String legado;

    EstadoSaga(String legado) { this.legado = legado; }

    /** Valor gravado em status_pagamento para que a versao antiga continue lendo. */
    public String legado() { return legado; }
}
