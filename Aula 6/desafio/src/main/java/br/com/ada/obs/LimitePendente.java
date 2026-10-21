package br.com.ada.obs;

import java.util.HashMap;
import java.util.Map;

/**
 * Teto do dinheiro em Pix ainda nao confirmado.
 * Passou do teto: a chamada ao PSP nao acontece. O valor em risco para de crescer.
 */
public final class LimitePendente {

    private final long tetoClienteCentavos;
    private final long tetoGlobalCentavos;
    private final Map<String, Long> porCliente = new HashMap<>();
    private final Map<String, Reserva> porPedido = new HashMap<>();
    private long global;

    public LimitePendente(long tetoClienteCentavos, long tetoGlobalCentavos) {
        this.tetoClienteCentavos = tetoClienteCentavos;
        this.tetoGlobalCentavos = tetoGlobalCentavos;
    }

    public synchronized boolean reservar(String clienteRef, String orderId, long centavos) {
        if (porPedido.containsKey(orderId)) return true;
        long doCliente = porCliente.getOrDefault(clienteRef, 0L);
        if (doCliente + centavos > tetoClienteCentavos) return false;
        if (global + centavos > tetoGlobalCentavos) return false;
        porCliente.put(clienteRef, doCliente + centavos);
        global += centavos;
        porPedido.put(orderId, new Reserva(clienteRef, centavos));
        return true;
    }

    public synchronized void liberar(String orderId) {
        Reserva r = porPedido.remove(orderId);
        if (r == null) return;
        global -= r.centavos;
        porCliente.put(r.cliente, porCliente.get(r.cliente) - r.centavos);
    }

    public synchronized long somaDoCliente(String clienteRef) {
        return porCliente.getOrDefault(clienteRef, 0L);
    }

    private record Reserva(String cliente, long centavos) {}
}
