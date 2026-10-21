package br.com.ada.obs;

/** Evento da saga. A chave de particao e o orderId. O traceparent vai no cabecalho da mensagem. */
public record EventoSaga(String chaveParticao, String orderId, String tipo, String traceparent, int particao) {}
