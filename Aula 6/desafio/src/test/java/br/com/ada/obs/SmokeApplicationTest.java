package br.com.ada.obs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmokeApplicationTest {

    @Value("${local.server.port}")
    int porta;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void sobeFinalizaSemCpfERecusaWebhookSemAssinatura() throws Exception {
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);

        String trace = TraceContext.criar().header();
        HttpResponse<String> ok = enviar("/checkout/finalizar",
                "{\"orderId\":\"48213\",\"clienteRef\":\"cli-771\",\"centavos\":18000,\"skuId\":\"FONE-BF\"}",
                "application/json", trace, null);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("CONFIRMADO").contains("48213").doesNotContain("cpf");

        HttpResponse<String> cpf = enviar("/checkout/finalizar",
                "{\"orderId\":\"48214\",\"clienteRef\":\"cli-771\",\"centavos\":18000,\"skuId\":\"FONE-BF\",\"cpf\":\"39053344705\"}",
                "application/json", null, null);
        assertThat(cpf.statusCode()).isEqualTo(400);

        HttpResponse<String> pedido = get("/checkout/pedidos/48213");
        assertThat(pedido.statusCode()).isEqualTo(200);
        assertThat(pedido.body()).contains("CONFIRMADO").doesNotContain("somaPendenteDoCliente");
        assertThat(get("/checkout/pedidos/nao-existe").statusCode()).isEqualTo(404);

        String metricas = get("/checkout/metricas").body();
        assertThat(metricas).contains("checkout_operacao_duracao_seconds").doesNotContain("order_id").doesNotContain("cpf");

        HttpResponse<String> webhook = enviar("/webhooks/psp", "pag-48213|48213|PAGO", "text/plain", null, "errada");
        assertThat(webhook.statusCode()).isEqualTo(401);
        assertThat(get("/checkout/pedidos/48213").body()).contains("CONFIRMADO");
    }

    private HttpResponse<String> get(String caminho) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> enviar(String caminho, String corpo, String tipo, String traceparent, String assinatura) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .header("Content-Type", tipo)
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (traceparent != null) b.header("traceparent", traceparent);
        if (assinatura != null) b.header("X-Assinatura", assinatura);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
