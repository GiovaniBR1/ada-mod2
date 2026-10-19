package br.com.ada.checkout;

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

    private HttpResponse<String> get(String caminho) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sobeComFlywayEResponde() throws Exception {
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        HttpResponse<String> pedido = get("/pedidos/3");
        assertThat(pedido.statusCode()).isEqualTo(200);
        assertThat(pedido.body()).contains("CANCELADO_COM_ESTORNO");
        assertThat(get("/pedidos/999").statusCode()).isEqualTo(404);
    }
}
