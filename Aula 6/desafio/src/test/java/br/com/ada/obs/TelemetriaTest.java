package br.com.ada.obs;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Numeros deste arquivo sao os mesmos do system-design.md.
 * 9.800 x 0,07 s + 200 x 2,57 s = media de 0,12 s, com p99 em 2,57 s.
 */
class TelemetriaTest {

    private static final Map<String, String> OK = Map.of(
            "servico", "checkout", "operacao", "finalizar", "resultado", "ok", "versao", "v1");

    @Test
    void mediaDe120msEscondeP99De2570ms() {
        Histograma h = new Histograma();
        for (int i = 0; i < 9_800; i++) h.observar(0.07, OK);
        for (int i = 0; i < 200; i++) h.observar(2.57, OK);

        assertThat(h.media()).isCloseTo(0.12, within(1e-9));
        assertThat(h.percentil(0.99)).isEqualTo(2.57);
        assertThat(h.percentil(0.50)).isEqualTo(0.07);
        // balde (2s, 4s]: 2 + (4-2) * (9900-9800) / 200 = 3,0 s
        assertThat(h.quantilPorBalde(0.99)).isEqualTo(3.0);

        assertThat(PainelAlertas.mediaAcimaDe(h.media(), 0.300)).isFalse();
        assertThat(PainelAlertas.quantilAcimaDe(h.quantilPorBalde(0.99), 0.800)).isTrue();
    }

    @Test
    void mediaDosP99EntreReplicasEscondeOCanary() {
        Histograma estavel = new Histograma();
        for (int i = 0; i < 1_000; i++) estavel.observar(0.1, OK);
        Histograma canary = new Histograma();
        Map<String, String> lento = Map.of(
                "servico", "checkout", "operacao", "finalizar", "resultado", "verificacao", "versao", "v2");
        for (int i = 0; i < 800; i++) canary.observar(0.1, lento);
        for (int i = 0; i < 200; i++) canary.observar(7.0, lento);

        double mediaDosP99 = (9 * estavel.percentil(0.99) + canary.percentil(0.99)) / 10.0;
        assertThat(estavel.percentil(0.99)).isEqualTo(0.1);
        assertThat(canary.percentil(0.99)).isEqualTo(7.0);
        assertThat(mediaDosP99).isCloseTo(0.79, within(1e-9));
        assertThat(PainelAlertas.quantilAcimaDe(mediaDosP99, 0.800)).isFalse();

        Histograma junto = new Histograma();
        for (int i = 0; i < 9_800; i++) junto.observar(0.1, OK);
        for (int i = 0; i < 200; i++) junto.observar(7.0, lento);
        assertThat(junto.percentil(0.99)).isEqualTo(7.0);
        assertThat(junto.quantilPorBalde(0.99)).isEqualTo(6.0);
        assertThat(junto.media()).isCloseTo(0.238, within(1e-9));
        assertThat(PainelAlertas.mediaAcimaDe(junto.media(), 0.300)).isFalse();
        assertThat(PainelAlertas.quantilAcimaDe(junto.quantilPorBalde(0.99), 0.800)).isTrue();
        assertThat(junto.series()).isEqualTo(2);
    }

    @Test
    void requisicoesAindaEmAndamentoNaoEntramNaMedia() {
        Histograma concluidas = new Histograma();
        for (int i = 0; i < 100; i++) concluidas.observar(0.12, OK);
        assertThat(concluidas.media()).isCloseTo(0.12, within(1e-9));
        assertThat(PainelAlertas.mediaAcimaDe(concluidas.media(), 0.300)).isFalse();

        // 4.900/s * 0,120 s = 588 em andamento no ritmo normal.
        // Mais 100/s segurando 8 s = 800. Total 1.388. Dobro da base = 1.176.
        int baseline = 588;
        int atual = 1_388;
        assertThat(PainelAlertas.emAndamentoAlto(atual, baseline)).isTrue();
        assertThat(PainelAlertas.emAndamentoAlto(baseline, baseline)).isFalse();
    }

    @Test
    void poolSaturadoDisparaComCpuBaixaEZeroHttp500() {
        PoolConexoes pool = new PoolConexoes("hikari", 10).ocupado(10, 200, 0);
        assertThat(pool.utilizacao()).isEqualTo(1.0);
        assertThat(PainelAlertas.poolSaturadoComPainelVerde(pool, 0.15, 0)).isTrue();
        PoolConexoes folgado = new PoolConexoes("hikari", 10).ocupado(2, 0, 0);
        assertThat(PainelAlertas.poolSaturadoComPainelVerde(folgado, 0.15, 0)).isFalse();
    }

    @Test
    void labelOrderIdOuCpfEProibidoENaoMultiplicaSerie() {
        Histograma h = new Histograma();
        assertThatThrownBy(() -> h.observar(0.1, Map.of("order_id", "48213")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.observar(0.1, Map.of("cpf", "39053344705")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.observar(0.1, Map.of("servico", "checkout", "operacao", "finalizar",
                "resultado", "39053344705", "versao", "v1")))
                .isInstanceOf(IllegalArgumentException.class);

        for (int i = 0; i < 1_000; i++) {
            h.observar(0.1, Map.of("servico", "checkout", "operacao", "finalizar", "resultado", "ok", "versao", i % 2 == 0 ? "v1" : "v2"));
        }
        assertThat(h.series()).isEqualTo(2);
        String texto = h.prometheus("checkout_operacao_duracao_seconds");
        assertThat(texto).doesNotContain("order_id").doesNotContain("cpf").doesNotContain("user_id");
        assertThat(texto).contains("servico=\"checkout\"");
    }

    @Test
    void headDescartaOTraceLentoETailGuarda() {
        Amostrador amostrador = new Amostrador();
        String traceId = String.format("%032x", 100);
        assertThat(amostrador.head(traceId, 0.01)).isFalse();
        assertThat(amostrador.tail(traceId, false, 2.57, 0.8, 0.01)).isTrue();
        assertThat(amostrador.tail(traceId, false, 0.07, 0.8, 0.01)).isFalse();
        assertThat(amostrador.tail(traceId, true, 0.07, 0.8, 0.01)).isTrue();
        assertThat(amostrador.head(String.format("%032x", 50), 0.01)).isTrue();
    }

    @Test
    void mdcNaoAtravessaThreadETraceparentAtravessa() throws Exception {
        TraceContext trace = TraceContext.criar();
        ContextoDaThread.abrir(trace, "48213");
        AtomicReference<String> visto = new AtomicReference<>("nao-rodou");
        Thread semPropagar = new Thread(() -> visto.set(ContextoDaThread.traceIdAtual()));
        semPropagar.start();
        semPropagar.join();
        assertThat(visto.get()).isNull();

        EventoSaga evento = new EventoSaga("48213", "48213", "FinalizarPedido", trace.header(), Particao.de("48213", 12));
        AtomicReference<String> continuado = new AtomicReference<>();
        Thread consumidor = new Thread(() -> {
            TraceContext filho = TraceContext.parse(evento.traceparent()).filho();
            ContextoDaThread.abrir(filho, evento.orderId());
            continuado.set(ContextoDaThread.traceIdAtual());
            ContextoDaThread.fechar();
        });
        consumidor.start();
        consumidor.join();
        assertThat(continuado.get()).isEqualTo(trace.traceId());
        assertThat(TraceContext.parse(evento.traceparent()).spanId()).isEqualTo(trace.spanId());
        ContextoDaThread.fechar();
    }

    @Test
    void traceparentRejeitaIdSoDeZerosELogNaoLevaCpf() {
        String zeros = "00-" + "0".repeat(32) + "-" + "0".repeat(16) + "-01";
        assertThat(TraceContext.parse(zeros)).isNull();
        TraceContext trace = TraceContext.criar();
        assertThat(trace.header()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(TraceContext.parse(trace.header()).traceId()).isEqualTo(trace.traceId());
        assertThat(trace.filho().traceId()).isEqualTo(trace.traceId());
        assertThat(trace.filho().spanId()).isNotEqualTo(trace.spanId());

        LogEstruturado logs = new LogEstruturado("chave-teste");
        String cpf = "39053344705";
        String ref = logs.pseudonimo(cpf);
        assertThat(ref).hasSize(12).doesNotContain(cpf);
        assertThat(logs.pseudonimo(cpf)).isEqualTo(ref);
        String linha = logs.registrar("INFO", "checkout", trace.traceId(), trace.spanId(),
                "48213", "PAGAMENTO_EM_VERIFICACAO", 120, "psp nao respondeu; consultando status antes de decidir", ref);
        assertThat(linha).doesNotContain(cpf).contains("\"trace_id\"").contains("\"order_id\":\"48213\"");
    }

    @Test
    void chaveSkuCaiNumaParticaoSoEOrderIdEspalha() {
        Set<Integer> porSku = new HashSet<>();
        Set<Integer> porPedido = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            porSku.add(Particao.de("FONE-BF", 12));
            porPedido.add(Particao.de("ped-" + i, 12));
        }
        assertThat(porSku).hasSize(1);
        assertThat(porPedido.size()).isGreaterThan(6);
        assertThat(Particao.de("48213", 12)).isEqualTo(Particao.de("48213", 12));
    }
}
