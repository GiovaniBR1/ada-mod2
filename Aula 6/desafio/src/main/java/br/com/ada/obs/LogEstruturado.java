package br.com.ada.obs;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Uma linha JSON por evento. CPF vira um pseudonimo (HMAC) e nao aparece na linha. */
public final class LogEstruturado {

    private final byte[] chave;
    private final List<String> linhas = new ArrayList<>();

    public LogEstruturado(String chaveHmac) {
        this.chave = chaveHmac.getBytes(StandardCharsets.UTF_8);
    }

    /** 12 hex do HMAC-SHA256. A chave fica fora do log. O mesmo CPF sempre gera o mesmo ref. */
    public String pseudonimo(String cpf) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(chave, "HmacSHA256"));
            byte[] bruto = mac.doFinal(cpf.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bruto).substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String registrar(String nivel, String service, String traceId, String spanId,
                            String orderId, String estado, long duracaoMs, String msg, String clienteRef) {
        String json = "{"
                + "\"ts\":\"" + Instant.now() + "\","
                + "\"level\":\"" + nivel + "\","
                + "\"service\":\"" + service + "\","
                + "\"trace_id\":\"" + traceId + "\","
                + "\"span_id\":\"" + spanId + "\","
                + "\"order_id\":\"" + orderId + "\","
                + "\"estado\":\"" + estado + "\","
                + "\"duracao_ms\":" + duracaoMs + ","
                + "\"cliente_ref\":\"" + clienteRef + "\","
                + "\"msg\":\"" + escapar(msg) + "\"}";
        linhas.add(json);
        return json;
    }

    public List<String> linhas() { return List.copyOf(linhas); }

    private static String escapar(String msg) {
        return msg.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
