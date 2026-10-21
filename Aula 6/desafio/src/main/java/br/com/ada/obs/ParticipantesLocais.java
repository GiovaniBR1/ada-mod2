package br.com.ada.obs;

/** Participantes usados so para o processo subir. Os testes de falha usam scripts proprios. */
final class EstoqueLocal implements Estoque {
    @Override public Status reservar(String orderId, String traceparent) { return Status.RESERVADO; }
    @Override public Status consultar(String orderId, String traceparent) { return Status.RESERVADO; }
    @Override public Status confirmar(String orderId, String traceparent) { return Status.CONFIRMADO; }
    @Override public void liberar(String orderId, String traceparent) { }
}

final class PspLocal implements Psp {
    @Override public Status cobrar(String orderId, long centavos, String traceparent) { return Status.PAGO; }
    @Override public Status consultarPagamento(String orderId, String traceparent) { return Status.PAGO; }
    @Override public Status estornar(String orderId, String traceparent) { return Status.ESTORNADO; }
    @Override public Status consultarEstorno(String orderId, String traceparent) { return Status.ESTORNADO; }
}
