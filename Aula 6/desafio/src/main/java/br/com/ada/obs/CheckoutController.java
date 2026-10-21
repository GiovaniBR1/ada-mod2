package br.com.ada.obs;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CheckoutController {

    private final CheckoutService checkout;

    public CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/checkout/finalizar")
    public ResponseEntity<Map<String, Object>> finalizar(@RequestBody Map<String, Object> corpo,
                                                         @RequestHeader(value = "traceparent", required = false) String traceparent) {
        if (corpo.containsKey("cpf") || corpo.containsKey("email") || corpo.containsKey("cartao")) {
            return ResponseEntity.badRequest().body(Map.of("erro", "dado pessoal nao entra no checkout"));
        }
        if (corpo.get("orderId") == null || corpo.get("clienteRef") == null || corpo.get("centavos") == null || corpo.get("skuId") == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", "campos obrigatorios: orderId, clienteRef, centavos, skuId"));
        }
        long centavos = ((Number) corpo.get("centavos")).longValue();
        CheckoutService.Resposta r = checkout.finalizar(
                corpo.get("orderId").toString(),
                corpo.get("clienteRef").toString(),
                centavos,
                corpo.get("skuId").toString(),
                traceparent);
        return ResponseEntity.status(r.http()).body(r.corpo());
    }

    @GetMapping("/checkout/pedidos/{orderId}")
    public ResponseEntity<Map<String, Object>> pedido(@PathVariable String orderId) {
        Pedido pedido = checkout.pedido(orderId);
        if (pedido == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(checkout.corpoPublico(pedido));
    }

    @PostMapping("/webhooks/psp")
    public ResponseEntity<Map<String, Object>> webhook(@RequestBody String corpo,
                                                       @RequestHeader(value = "X-Assinatura", required = false) String assinatura) {
        CheckoutService.Resposta r = checkout.receberWebhook(corpo, assinatura);
        return ResponseEntity.status(r.http()).body(r.corpo());
    }

    @GetMapping(value = "/checkout/metricas", produces = MediaType.TEXT_PLAIN_VALUE)
    public String metricas() {
        return checkout.textoMetricas();
    }
}
