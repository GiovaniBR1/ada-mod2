package br.com.ada.obs;

/**
 * Equivalente ao MDC do SLF4J: um mapa preso na thread (ThreadLocal).
 * Uma thread nova, um consumidor do Kafka ou um {@code @Async} NAO herda esse mapa.
 * O que atravessa a fronteira e o traceparent carregado de proposito no HTTP ou no evento.
 */
public final class ContextoDaThread {

    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();
    private static final ThreadLocal<String> ORDER = new ThreadLocal<>();

    private ContextoDaThread() {}

    public static void abrir(TraceContext trace, String orderId) {
        TRACE.set(trace.traceId());
        ORDER.set(orderId);
    }

    public static String traceIdAtual() { return TRACE.get(); }

    public static String orderIdAtual() { return ORDER.get(); }

    public static void fechar() {
        TRACE.remove();
        ORDER.remove();
    }
}
