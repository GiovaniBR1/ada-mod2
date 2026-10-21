package br.com.ada.obs;

public interface Estoque {
    enum Status { RESERVADO, SEM_SALDO, NAO_RESERVADO, CONFIRMADO, RESERVA_EXPIROU, SEM_RESPOSTA, DESCONHECIDO }

    Status reservar(String orderId, String traceparent);

    Status consultar(String orderId, String traceparent);

    Status confirmar(String orderId, String traceparent);

    void liberar(String orderId, String traceparent);
}
