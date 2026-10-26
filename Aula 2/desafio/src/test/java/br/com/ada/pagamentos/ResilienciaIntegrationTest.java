package br.com.ada.pagamentos;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** "Engenharia do caos local": WireMock faz o papel do parceiro instável. */
@SpringBootTest
@AutoConfigureMockMvc
class ResilienciaIntegrationTest {

    static final int PORTA = portaLivre();
    static final WireMockServer parceiro = new WireMockServer(WireMockConfiguration.options().port(PORTA));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("parceiro.base-url", () -> "http://localhost:" + PORTA);
    }

    @Autowired MockMvc mvc;
    @Autowired CircuitBreakerRegistry registry;
    @Autowired IdempotenciaStore idempotencia;
    @Autowired ContingenciaStore contingencia;

    CircuitBreaker cb() { return registry.circuitBreaker("parceiro"); }

    @BeforeAll static void sobe() { parceiro.start(); }
    @AfterAll static void desce() { parceiro.stop(); }

    @BeforeEach
    void limpa() {
        if (!parceiro.isRunning()) parceiro.start();
        parceiro.resetAll();
        cb().reset();
        idempotencia.limpar();
        contingencia.limpar();
    }

    // ---------- utilitários ----------
    static int portaLivre() {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); } catch (Exception e) { throw new RuntimeException(e); }
    }
    ResultActions pagar(String chave, String id, String valor) throws Exception {
        var req = post("/pagamentos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\",\"valor\":" + valor + "}");
        if (chave != null) req.header("Idempotency-Key", chave);
        return mvc.perform(req);
    }
    ResultActions pagar(String chave) throws Exception { return pagar(chave, "pg-1", "100.00"); }
    static String chave() { return UUID.randomUUID().toString(); }
    void parceiroResponde(int status, String corpo, int atrasoMs) {
        parceiro.stubFor(WireMock.post("/cobrancas").willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(corpo).withFixedDelay(atrasoMs)));
    }
    void parceiroAprova() { parceiroResponde(200, "{\"id\":\"pg-1\",\"status\":\"APROVADO\"}", 0); }
    int chamadasAoParceiro() { return parceiro.countRequestsMatching(postRequestedFor(urlEqualTo("/cobrancas")).build()).getCount(); }
    void abreCircuito() throws Exception {
        parceiroResponde(500, "{}", 0);
        for (int i = 0; i < 4; i++) pagar(chave()).andExpect(status().isAccepted());
        assertThat(cb().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ---------- caminho feliz ----------
    @Test
    void sucesso_devolve201_eEnviaIdempotencyKeyNoCabecalho() throws Exception {
        parceiroAprova();
        var k = chave();
        pagar(k).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("pg-1"))
                .andExpect(jsonPath("$.status").value("APROVADO"));
        parceiro.verify(1, postRequestedFor(urlEqualTo("/cobrancas"))
                .withHeader("Idempotency-Key", equalTo(k))
                .withRequestBody(equalToJson("{\"id\":\"pg-1\",\"valor\":100.00}")));
    }

    @Test
    void semIdempotencyKey_devolve400ProblemDetails_eNaoChamaParceiro() throws Exception {
        parceiroAprova();
        pagar(null).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(chamadasAoParceiro()).isZero();
    }

    // ---------- lentidão (read timeout) ----------
    @Test
    void parceiroLento3s_cortaNoReadTimeout_eDevolve202Pendente() throws Exception {
        parceiroResponde(200, "{\"id\":\"pg-1\",\"status\":\"APROVADO\"}", 3000);
        long t0 = System.currentTimeMillis();
        pagar(chave()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDENTE"));
        long ms = System.currentTimeMillis() - t0;
        System.out.println(">> parceiro com 3s de atraso; nossa API respondeu em " + ms + " ms");
        assertThat(ms).isLessThan(2900);
        assertThat(contingencia.totalPendentes()).isEqualTo(1);
    }

    // ---------- connect vs read ----------
    @Test
    void conexaoRecusada_devolve503ComRetryAfter_porqueNadaFoiCobrado() throws Exception {
        parceiro.stop(); // porta fechada -> ConnectException
        pagar(chave()).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://exemplo.ada/problemas/parceiro-sem-conexao"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    // ---------- 5xx e 429 ----------
    @Test
    void erro500DoParceiro_eAmbiguo_devolve202Pendente_nuncaAprovado() throws Exception {
        parceiroResponde(500, "{}", 0);
        pagar(chave()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDENTE"));
    }

    @Test
    void erro429DoParceiro_tresChamadas_gravamPendente_naoPropaga429() throws Exception {
        parceiroResponde(429, "{}", 0);
        pagar(chave()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDENTE"));
        assertThat(chamadasAoParceiro()).isEqualTo(3);
    }

    @Test
    void erros5xxIntermitentes_respostasPadronizadas() throws Exception {
        parceiro.stubFor(WireMock.post("/cobrancas").inScenario("intermitente").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        parceiro.stubFor(WireMock.post("/cobrancas").inScenario("intermitente").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"id\":\"pg-1\",\"status\":\"APROVADO\"}")));
        var k = chave();
        pagar(k).andExpect(status().isCreated()); // 503 e depois 200, na mesma chamada do cliente
        parceiro.verify(2, postRequestedFor(urlEqualTo("/cobrancas")).withHeader("Idempotency-Key", equalTo(k)));
    }

    // ---------- circuit breaker ----------
    @Test
    void sequenciaDeFalhas_abreOCircuito() throws Exception {
        abreCircuito();
    }

    @Test
    void circuitoAberto_respondeNaHora503_semChamarParceiro() throws Exception {
        abreCircuito();
        int antes = chamadasAoParceiro();
        parceiroResponde(200, "{}", 3000); // mesmo lento, não deve ser chamado
        long t0 = System.currentTimeMillis();
        pagar(chave()).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.type").value("https://exemplo.ada/problemas/circuito-aberto"));
        long ms = System.currentTimeMillis() - t0;
        System.out.println(">> circuito OPEN; nossa API respondeu em " + ms + " ms");
        assertThat(ms).isLessThan(200);
        assertThat(chamadasAoParceiro()).isEqualTo(antes);
    }

    @Test
    void recuperacao_OPEN_HALFOPEN_CLOSED() throws Exception {
        abreCircuito();
        parceiroAprova();
        Thread.sleep(2100); // wait-duration-in-open-state = 2s
        pagar(chave()).andExpect(status().isCreated()); // 1ª chamada de teste
        assertThat(cb().getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        pagar(chave()).andExpect(status().isCreated()); // 2ª chamada de teste
        assertThat(cb().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---------- idempotência ----------
    @Test
    void reenvioAposTimeout_devolveMesma202_semNovaChamada() throws Exception {
        parceiroResponde(200, "{\"id\":\"pg-1\",\"status\":\"APROVADO\"}", 3000);
        var k = chave();
        pagar(k).andExpect(status().isAccepted());
        pagar(k).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDENTE"));
        Thread.sleep(2500); // read timeout de 2 s; deixa o atraso de 3 s do WireMock acabar antes de contar
        assertThat(chamadasAoParceiro()).isEqualTo(1);
    }

    @Test
    void recusa402_devolve402_eNaoAbreOCircuito() throws Exception {
        parceiroResponde(402, "{}", 0);
        var k = chave();
        pagar(k).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("RECUSADO"));
        assertThat(cb().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        pagar(k).andExpect(status().isPaymentRequired());
        assertThat(chamadasAoParceiro()).isEqualTo(1);
    }

    @Test
    void mesmaChaveComOutroCorpo_devolve422() throws Exception {
        parceiroAprova();
        var k = chave();
        pagar(k, "pg-1", "100.00").andExpect(status().isCreated());
        pagar(k, "pg-1", "999.00").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void duasRequisicoesSimultaneasMesmaChave_umaVence_outraRecebe409() throws Exception {
        parceiroResponde(200, "{\"id\":\"pg-1\",\"status\":\"APROVADO\"}", 500);
        var k = chave();
        var primeira = CompletableFuture.supplyAsync(() -> {
            try { return pagar(k).andReturn(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(150);
        pagar(k).andExpect(status().isConflict());
        MvcResult r = primeira.get();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(chamadasAoParceiro()).isEqualTo(1);
    }

    // ---------- falha no fallback ----------
    @Test
    void contingenciaTambemFalha_devolve500ProblemDetails_semStackTrace() throws Exception {
        parceiroResponde(500, "{}", 0);
        contingencia.setDisponivel(false);
        var r = pagar(chave()).andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://exemplo.ada/problemas/contingencia-indisponivel"))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("at br.com");
    }
}
