package br.com.ada.obs;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cabecalho W3C traceparent: {@code 00-<trace-id 32 hex>-<span-id 16 hex>-<flags>}.
 * O trace-id e o mesmo do pedido inteiro. Cada salto (HTTP ou evento) cria um span-id novo.
 */
public final class TraceContext {

    private final String traceId;
    private final String spanId;
    private final boolean amostrado;

    private TraceContext(String traceId, String spanId, boolean amostrado) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.amostrado = amostrado;
    }

    public static TraceContext criar() {
        return new TraceContext(hex(16), hex(8), true);
    }

    /** Devolve null se o cabecalho nao segue a spec (inclusive id so de zeros, que a W3C rejeita). */
    public static TraceContext parse(String header) {
        if (header == null) {
            return null;
        }
        String[] p = header.trim().split("-");
        if (p.length != 4 || !p[0].equals("00") || p[1].length() != 32 || p[2].length() != 16 || p[3].length() != 2) {
            return null;
        }
        if (!hexDe(p[1]) || !hexDe(p[2]) || !hexDe(p[3])) {
            return null;
        }
        if (soZero(p[1]) || soZero(p[2])) {
            return null;
        }
        return new TraceContext(p[1].toLowerCase(), p[2].toLowerCase(), p[3].charAt(1) == '1');
    }

    public static TraceContext continuarOuCriar(String header) {
        TraceContext existente = parse(header);
        return existente == null ? criar() : existente;
    }

    /** Mesmo trace-id, span novo. E assim que o Estoque e o consumidor do evento entram no mesmo trace. */
    public TraceContext filho() {
        return new TraceContext(traceId, hex(8), amostrado);
    }

    public String header() {
        return "00-" + traceId + "-" + spanId + "-" + (amostrado ? "01" : "00");
    }

    public String traceId() { return traceId; }
    public String spanId() { return spanId; }
    public boolean amostrado() { return amostrado; }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static boolean hexDe(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private static boolean soZero(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '0') return false;
        }
        return true;
    }
}
