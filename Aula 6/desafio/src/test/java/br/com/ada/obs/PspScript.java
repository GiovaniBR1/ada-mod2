package br.com.ada.obs;

import java.util.ArrayDeque;
import java.util.Queue;

final class PspScript implements Psp {
    private final Queue<Status> cobrar = new ArrayDeque<>();
    private final Queue<Status> consultarPagamento = new ArrayDeque<>();
    private final Queue<Status> estornar = new ArrayDeque<>();
    private final Queue<Status> consultarEstorno = new ArrayDeque<>();
    private int chamadasCobrar;
    private int chamadasEstornar;
    private String ultimoTraceparent;

    PspScript cobrar(Status... s) { cobrar.addAll(java.util.List.of(s)); return this; }
    PspScript consultarPagamento(Status... s) { consultarPagamento.addAll(java.util.List.of(s)); return this; }
    PspScript estornar(Status... s) { estornar.addAll(java.util.List.of(s)); return this; }
    PspScript consultarEstorno(Status... s) { consultarEstorno.addAll(java.util.List.of(s)); return this; }

    int chamadasCobrar() { return chamadasCobrar; }
    int chamadasEstornar() { return chamadasEstornar; }
    String ultimoTraceparent() { return ultimoTraceparent; }

    @Override public Status cobrar(String orderId, long centavos, String traceparent) {
        chamadasCobrar++;
        ultimoTraceparent = traceparent;
        return tirar(cobrar, "cobrar");
    }

    @Override public Status consultarPagamento(String orderId, String traceparent) {
        ultimoTraceparent = traceparent;
        return tirar(consultarPagamento, "consultarPagamento");
    }

    @Override public Status estornar(String orderId, String traceparent) {
        chamadasEstornar++;
        ultimoTraceparent = traceparent;
        return tirar(estornar, "estornar");
    }

    @Override public Status consultarEstorno(String orderId, String traceparent) {
        ultimoTraceparent = traceparent;
        return tirar(consultarEstorno, "consultarEstorno");
    }

    private static Status tirar(Queue<Status> fila, String nome) {
        if (fila.isEmpty()) throw new IllegalStateException("script de psp acabou em " + nome);
        return fila.remove();
    }
}
