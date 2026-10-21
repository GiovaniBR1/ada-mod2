package br.com.ada.obs;

public interface Psp {
    enum Status { PAGO, RECUSADO, PIX_GERADO, ESTORNADO, NAO_ESTORNADO, SEM_RESPOSTA, DESCONHECIDO }

    Status cobrar(String orderId, long centavos, String traceparent);

    Status consultarPagamento(String orderId, String traceparent);

    Status estornar(String orderId, String traceparent);

    Status consultarEstorno(String orderId, String traceparent);
}
