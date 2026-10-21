package br.com.ada.obs;

import java.util.ArrayDeque;
import java.util.Queue;

final class EstoqueScript implements Estoque {
    private final Queue<Status> reservar = new ArrayDeque<>();
    private final Queue<Status> consultar = new ArrayDeque<>();
    private final Queue<Status> confirmar = new ArrayDeque<>();
    private int chamadasReservar;
    private int chamadasConfirmar;
    private int chamadasLiberar;
    private String ultimoTraceparent;
    private Runnable aoReservar = () -> { };

    EstoqueScript reservar(Status... s) { reservar.addAll(java.util.List.of(s)); return this; }
    EstoqueScript consultar(Status... s) { consultar.addAll(java.util.List.of(s)); return this; }
    EstoqueScript confirmar(Status... s) { confirmar.addAll(java.util.List.of(s)); return this; }
    void aoReservar(Runnable r) { this.aoReservar = r; }

    int chamadasReservar() { return chamadasReservar; }
    int chamadasConfirmar() { return chamadasConfirmar; }
    int chamadasLiberar() { return chamadasLiberar; }
    String ultimoTraceparent() { return ultimoTraceparent; }

    @Override public Status reservar(String orderId, String traceparent) {
        chamadasReservar++;
        ultimoTraceparent = traceparent;
        aoReservar.run();
        return tirar(reservar, "reservar");
    }

    @Override public Status consultar(String orderId, String traceparent) {
        ultimoTraceparent = traceparent;
        return tirar(consultar, "consultar");
    }

    @Override public Status confirmar(String orderId, String traceparent) {
        chamadasConfirmar++;
        ultimoTraceparent = traceparent;
        return tirar(confirmar, "confirmar");
    }

    @Override public void liberar(String orderId, String traceparent) {
        chamadasLiberar++;
        ultimoTraceparent = traceparent;
    }

    private static Status tirar(Queue<Status> fila, String nome) {
        if (fila.isEmpty()) throw new IllegalStateException("script de estoque acabou em " + nome);
        return fila.remove();
    }
}
