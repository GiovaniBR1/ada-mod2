package br.com.ada.obs;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class WebhookAssinatura {

    private WebhookAssinatura() {}

    public static String assinar(String segredo, String corpo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean confere(String segredo, String corpo, String assinaturaRecebida) {
        if (assinaturaRecebida == null || assinaturaRecebida.isBlank()) {
            return false;
        }
        byte[] esperado = assinar(segredo, corpo).getBytes(StandardCharsets.UTF_8);
        byte[] recebido = assinaturaRecebida.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(esperado, recebido);
    }
}
