package br.com.ada.obs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Fecha o pedido e registra o que a investigacao precisa ver.
 * Se um participante nao responde, consulta o status dele antes de cancelar, estornar ou confirmar.
 */
public final class CheckoutService {

    public static final long PRAZO_REVISAO_MS = 24L * 60 * 60 * 1000;
    public static final int PARTICICOES = 12;

    private final Estoque estoque;
    private final Psp psp;
    private final LimitePendente limite;
    private final LogEstruturado logs;
    private final Histograma histograma;
    private final String versao;
    private final String segredoWebhook;
    private final LongSupplier relogio;
    private final Map<String, Pedido> pedidos = new ConcurrentHashMap<>();
    private final List<EventoSaga> eventos = new ArrayList<>();
    private final Set<String> webhooksVistos = ConcurrentHashMap.newKeySet();
    private final AtomicInteger httpEmAndamento = new AtomicInteger();

    public CheckoutService(Estoque estoque, Psp psp, LimitePendente limite, LogEstruturado logs,
                           Histograma histograma, String versao, String segredoWebhook, LongSupplier relogio) {
        this.estoque = estoque;
        this.psp = psp;
        this.limite = limite;
        this.logs = logs;
        this.histograma = histograma;
        this.versao = versao;
        this.segredoWebhook = segredoWebhook;
        this.relogio = relogio;
    }

    public Resposta finalizar(String orderId, String clienteRef, long centavos, String skuId, String traceparentEntrada) {
        Pedido ja = pedidos.get(orderId);
        if (ja != null) {
            return resposta(ja, ja.estado().terminalParaCliente() ? 200 : 202);
        }
        TraceContext span = TraceContext.continuarOuCriar(traceparentEntrada).filho();
        ContextoDaThread.abrir(span, orderId);
        Pedido pedido = new Pedido(orderId, clienteRef, skuId, centavos, span.traceId(), span.header());
        pedidos.put(orderId, pedido);
        long inicio = System.nanoTime();
        httpEmAndamento.incrementAndGet();
        try {
            publicar(pedido, "FinalizarPedido");
            reservar(pedido);
            if (pedido.estado() == EstadoPedido.ESTOQUE_EM_VERIFICACAO || pedido.estado().terminalParaCliente()) {
                return resposta(pedido, pedido.estado().terminalParaCliente() ? 200 : 202);
            }
            cobrar(pedido);
            if (!pedido.pagamentoCapturado()) {
                int http = pedido.estado().terminalParaCliente() ? 200 : 202;
                return resposta(pedido, http);
            }
            confirmarEstoque(pedido);
            int http = pedido.estado().terminalParaCliente() ? 200 : 202;
            return resposta(pedido, http);
        } finally {
            httpEmAndamento.decrementAndGet();
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            histograma.observar(ms / 1000.0, Map.of(
                    "servico", "checkout",
                    "operacao", "finalizar",
                    "resultado", resultadoDe(pedido.estado()),
                    "versao", versao));
            logs.registrar("INFO", "checkout", pedido.traceId(), span.spanId(), orderId,
                    pedido.estado().name(), ms, "finalizar encerrou o http", clienteRef);
            ContextoDaThread.fechar();
        }
    }

    public Resposta receberWebhook(String corpo, String assinatura) {
        if (!WebhookAssinatura.confere(segredoWebhook, corpo, assinatura)) {
            return new Resposta(401, Map.of("resultado", "assinatura_invalida"), null);
        }
        String[] partes = corpo.split("\\|");
        if (partes.length != 3) {
            return new Resposta(400, Map.of("resultado", "corpo_invalido"), null);
        }
        String pagamentoId = partes[0];
        String orderId = partes[1];
        String status = partes[2];
        if (!webhooksVistos.add(pagamentoId + "|" + status)) {
            Pedido repetido = pedidos.get(orderId);
            return new Resposta(200, Map.of("resultado", "repetido"), repetido);
        }
        Pedido pedido = pedidos.get(orderId);
        if (pedido == null || pedido.pagamentoId() == null || !pedido.pagamentoId().equals(pagamentoId)) {
            webhooksVistos.remove(pagamentoId + "|" + status);
            return new Resposta(404, Map.of("resultado", "pagamento_desconhecido"), pedido);
        }
        TraceContext filho = TraceContext.parse(pedido.traceparent()).filho();
        ContextoDaThread.abrir(filho, orderId);
        try {
            if ("PAGO".equals(status)) {
                aplicarPago(pedido);
            } else if ("ESTORNADO".equals(status)) {
                if (pedido.estornoConfirmado() || pedido.estado() == EstadoPedido.CANCELADO_COM_ESTORNO) {
                    return new Resposta(200, Map.of("resultado", "repetido", "estado", pedido.estado().name()), pedido);
                }
                if (pedido.pagamentoCapturado() && pedido.estado() == EstadoPedido.ESTORNO_EM_REVISAO) {
                    pedido.estornoConfirmado(true);
                    pedido.estado(EstadoPedido.CANCELADO_COM_ESTORNO);
                    log(pedido, "webhook confirmou o estorno; nenhuma chamada nova ao PSP");
                }
            }
            return new Resposta(200, corpoPublico(pedido), pedido);
        } finally {
            ContextoDaThread.fechar();
        }
    }

    /** Evento repetido ou atrasado: a transicao so ocorre se o estado atual ainda e o esperado. */
    public boolean aplicarEvento(String orderId, String tipo) {
        Pedido pedido = pedidos.get(orderId);
        if (pedido == null) return false;
        if ("PagamentoAprovado".equals(tipo)) {
            if (pedido.estado() == EstadoPedido.CANCELADO_SEM_COBRANCA) {
                if (pedido.pagamentoId() == null && !pedido.pagamentoCapturado()) {
                    return false;
                }
                pedido.pagamentoCapturado(true);
                pedido.estado(EstadoPedido.ESTORNANDO);
                return true;
            }
            if (pedido.estado() != EstadoPedido.AGUARDANDO_PAGAMENTO
                    && pedido.estado() != EstadoPedido.PAGAMENTO_EM_VERIFICACAO) {
                return false;
            }
            aplicarPago(pedido);
            return true;
        }
        if ("ReservaExpirou".equals(tipo)) {
            if (pedido.estado() == EstadoPedido.CANCELADO_COM_ESTORNO || pedido.estornoConfirmado()) {
                return false;
            }
            if (pedido.pagamentoCapturado()
                    && (pedido.estado() == EstadoPedido.CONFIRMACAO_EM_VERIFICACAO
                    || pedido.estado() == EstadoPedido.CONCLUIDO)) {
                pedido.reservado(false);
                estornar(pedido);
                return true;
            }
            return false;
        }
        return false;
    }

    public void reconciliar(long agoraMs) {
        for (Pedido pedido : List.copyOf(pedidos.values())) {
            switch (pedido.estado()) {
                case ESTOQUE_EM_VERIFICACAO -> retomarEstoque(pedido);
                case PAGAMENTO_EM_VERIFICACAO, AGUARDANDO_PAGAMENTO -> retomarPagamento(pedido);
                case CONFIRMACAO_EM_VERIFICACAO -> retomarConfirmacao(pedido);
                case ESTORNO_EM_REVISAO, ESTORNO_FALHOU -> retomarEstorno(pedido, agoraMs);
                default -> { }
            }
        }
    }

    public Pedido pedido(String orderId) { return pedidos.get(orderId); }
    public List<EventoSaga> eventos() { return List.copyOf(eventos); }
    public int httpEmAndamento() { return httpEmAndamento.get(); }
    public int pedidosEmAberto() {
        return (int) pedidos.values().stream().filter(p -> !p.estado().terminalParaCliente()).count();
    }
    public Histograma histograma() { return histograma; }

    /** Texto no formato do Prometheus: histograma RED e os dois medidores de trabalho em aberto. */
    public String textoMetricas() {
        return histograma.prometheus("checkout_operacao_duracao_seconds")
                + "# TYPE checkout_http_em_andamento gauge\n"
                + "checkout_http_em_andamento " + httpEmAndamento.get() + "\n"
                + "# TYPE checkout_pedidos_em_aberto gauge\n"
                + "checkout_pedidos_em_aberto " + pedidosEmAberto() + "\n";
    }
    public String segredoWebhook() { return segredoWebhook; }

    private void reservar(Pedido pedido) {
        String tp = filho(pedido);
        Estoque.Status s = estoque.reservar(pedido.orderId(), tp);
        if (s == Estoque.Status.SEM_RESPOSTA) {
            pedido.estado(EstadoPedido.ESTOQUE_EM_VERIFICACAO);
            log(pedido, "estoque nao respondeu; consultando status antes de decidir");
            s = consultarEstoque(pedido);
            if (s == Estoque.Status.DESCONHECIDO || s == Estoque.Status.SEM_RESPOSTA) {
                pedido.estado(EstadoPedido.ESTOQUE_EM_VERIFICACAO);
                pedido.entrouEmRevisaoMs(relogio.getAsLong());
                return;
            }
        }
        aplicarReserva(pedido, s);
    }

    private void aplicarReserva(Pedido pedido, Estoque.Status s) {
        if (s == Estoque.Status.RESERVADO || s == Estoque.Status.CONFIRMADO) {
            pedido.reservado(true);
            if (s == Estoque.Status.CONFIRMADO) pedido.estoqueConfirmado(true);
            pedido.estado(EstadoPedido.PENDENTE);
            return;
        }
        if (s == Estoque.Status.SEM_SALDO || s == Estoque.Status.NAO_RESERVADO) {
            cancelarSemCobranca(pedido, "sem reserva de estoque");
            return;
        }
        pedido.estado(EstadoPedido.ESTOQUE_EM_VERIFICACAO);
        pedido.entrouEmRevisaoMs(relogio.getAsLong());
    }

    private void cobrar(Pedido pedido) {
        if (!limite.reservar(pedido.clienteRef(), pedido.orderId(), pedido.centavos())) {
            if (pedido.reservado()) {
                estoque.liberar(pedido.orderId(), filho(pedido));
                pedido.reservado(false);
            }
            cancelarSemCobranca(pedido, "teto de pagamentos pendentes");
            return;
        }
        String tp = filho(pedido);
        pedido.pagamentoId("pag-" + pedido.orderId());
        Psp.Status s = psp.cobrar(pedido.orderId(), pedido.centavos(), tp);
        if (s == Psp.Status.SEM_RESPOSTA) {
            pedido.estado(EstadoPedido.PAGAMENTO_EM_VERIFICACAO);
            log(pedido, "psp nao respondeu; consultando status antes de decidir");
            s = consultarPagamento(pedido);
            if (s == Psp.Status.DESCONHECIDO || s == Psp.Status.SEM_RESPOSTA) {
                pedido.estado(EstadoPedido.PAGAMENTO_EM_VERIFICACAO);
                pedido.entrouEmRevisaoMs(relogio.getAsLong());
                return;
            }
        }
        aplicarPagamento(pedido, s);
    }

    private void aplicarPagamento(Pedido pedido, Psp.Status s) {
        if (s == Psp.Status.PAGO) {
            pedido.pagamentoCapturado(true);
            limite.liberar(pedido.orderId());
            return;
        }
        if (s == Psp.Status.PIX_GERADO) {
            pedido.estado(EstadoPedido.AGUARDANDO_PAGAMENTO);
            log(pedido, "pix gerado; o valor entra no teto de pendente");
            return;
        }
        if (s == Psp.Status.RECUSADO) {
            limite.liberar(pedido.orderId());
            if (pedido.reservado()) {
                estoque.liberar(pedido.orderId(), filho(pedido));
                pedido.reservado(false);
            }
            cancelarSemCobranca(pedido, "psp recusou; nada foi cobrado");
            return;
        }
        pedido.estado(EstadoPedido.PAGAMENTO_EM_VERIFICACAO);
        pedido.entrouEmRevisaoMs(relogio.getAsLong());
    }

    private void confirmarEstoque(Pedido pedido) {
        if (!pedido.pagamentoCapturado()) {
            pedido.estado(EstadoPedido.PAGAMENTO_EM_VERIFICACAO);
            return;
        }
        Estoque.Status s = estoque.confirmar(pedido.orderId(), filho(pedido));
        if (s == Estoque.Status.SEM_RESPOSTA) {
            pedido.estado(EstadoPedido.CONFIRMACAO_EM_VERIFICACAO);
            log(pedido, "confirmacao de estoque sem resposta; consultando status antes de decidir");
            s = consultarEstoque(pedido);
            if (s == Estoque.Status.DESCONHECIDO || s == Estoque.Status.SEM_RESPOSTA) {
                pedido.estado(EstadoPedido.CONFIRMACAO_EM_VERIFICACAO);
                pedido.entrouEmRevisaoMs(relogio.getAsLong());
                return;
            }
        }
        aplicarConfirmacao(pedido, s);
    }

    private void aplicarConfirmacao(Pedido pedido, Estoque.Status s) {
        if (s == Estoque.Status.CONFIRMADO) {
            if (!pedido.reservado()) {
                pedido.estado(EstadoPedido.CONFIRMACAO_EM_VERIFICACAO);
                log(pedido, "confirmacao sem reserva conhecida; pedido nao vai para CONCLUIDO");
                return;
            }
            pedido.estoqueConfirmado(true);
            pedido.estado(EstadoPedido.CONCLUIDO);
            log(pedido, "pedido confirmado com reserva e pagamento");
            return;
        }
        if (s == Estoque.Status.RESERVADO) {
            pedido.reservado(true);
            Estoque.Status deNovo = estoque.confirmar(pedido.orderId(), filho(pedido));
            if (deNovo == Estoque.Status.CONFIRMADO) {
                pedido.estoqueConfirmado(true);
                pedido.estado(EstadoPedido.CONCLUIDO);
                return;
            }
            if (deNovo == Estoque.Status.RESERVA_EXPIROU || deNovo == Estoque.Status.NAO_RESERVADO) {
                pedido.reservado(false);
                estornar(pedido);
                return;
            }
            pedido.estado(EstadoPedido.CONFIRMACAO_EM_VERIFICACAO);
            pedido.entrouEmRevisaoMs(relogio.getAsLong());
            return;
        }
        if (s == Estoque.Status.RESERVA_EXPIROU || s == Estoque.Status.NAO_RESERVADO || s == Estoque.Status.SEM_SALDO) {
            pedido.reservado(false);
            estornar(pedido);
            return;
        }
        pedido.estado(EstadoPedido.CONFIRMACAO_EM_VERIFICACAO);
        pedido.entrouEmRevisaoMs(relogio.getAsLong());
    }

    private void estornar(Pedido pedido) {
        if (pedido.estornoConfirmado()) {
            pedido.estado(EstadoPedido.CANCELADO_COM_ESTORNO);
            return;
        }
        if (!pedido.pagamentoCapturado()) {
            cancelarSemCobranca(pedido, "estorno nao chamado: pagamento nao foi capturado");
            return;
        }
        if (pedido.estornoSolicitado()) {
            pedido.estado(EstadoPedido.ESTORNO_EM_REVISAO);
            return;
        }
        pedido.estornoSolicitado(true);
        Psp.Status s = psp.estornar(pedido.orderId(), filho(pedido));
        if (s == Psp.Status.SEM_RESPOSTA) {
            pedido.estado(EstadoPedido.ESTORNO_EM_REVISAO);
            pedido.entrouEmRevisaoMs(relogio.getAsLong());
            log(pedido, "estorno sem resposta; consultando status antes de decidir");
            s = consultarEstorno(pedido);
            if (s == Psp.Status.DESCONHECIDO || s == Psp.Status.SEM_RESPOSTA) {
                return;
            }
        }
        aplicarResultadoEstorno(pedido, s);
    }

    private void aplicarResultadoEstorno(Pedido pedido, Psp.Status s) {
        if (s == Psp.Status.ESTORNADO) {
            pedido.estornoConfirmado(true);
            pedido.estado(EstadoPedido.CANCELADO_COM_ESTORNO);
            limite.liberar(pedido.orderId());
            log(pedido, "cancelado com estorno");
            return;
        }
        pedido.estado(EstadoPedido.ESTORNO_EM_REVISAO);
        pedido.entrouEmRevisaoMs(relogio.getAsLong());
        log(pedido, "estorno ainda nao confirmado; fica em revisao para o job");
    }

    private void cancelarSemCobranca(Pedido pedido, String motivo) {
        if (pedido.pagamentoCapturado()) {
            estornar(pedido);
            return;
        }
        limite.liberar(pedido.orderId());
        pedido.estado(EstadoPedido.CANCELADO_SEM_COBRANCA);
        log(pedido, motivo);
    }

    private void aplicarPago(Pedido pedido) {
        if (pedido.estado() != EstadoPedido.AGUARDANDO_PAGAMENTO
                && pedido.estado() != EstadoPedido.PAGAMENTO_EM_VERIFICACAO) {
            return;
        }
        pedido.pagamentoCapturado(true);
        limite.liberar(pedido.orderId());
        confirmarEstoque(pedido);
    }

    private void retomarEstoque(Pedido pedido) {
        Estoque.Status s = consultarEstoque(pedido);
        if (s == Estoque.Status.DESCONHECIDO || s == Estoque.Status.SEM_RESPOSTA) {
            log(pedido, "job consultou o estoque e o status segue desconhecido; nao cancela nem cobra");
            return;
        }
        aplicarReserva(pedido, s);
        if (pedido.reservado() && !pedido.pagamentoCapturado() && !pedido.estado().terminalParaCliente()) {
            cobrar(pedido);
            if (pedido.pagamentoCapturado()) confirmarEstoque(pedido);
        }
    }

    private void retomarPagamento(Pedido pedido) {
        Psp.Status s = consultarPagamento(pedido);
        if (s == Psp.Status.DESCONHECIDO || s == Psp.Status.SEM_RESPOSTA) {
            log(pedido, "job consultou o psp e o status segue desconhecido; nao estorna nem cancela");
            return;
        }
        if (s == Psp.Status.PAGO) {
            pedido.pagamentoCapturado(true);
            limite.liberar(pedido.orderId());
            confirmarEstoque(pedido);
            return;
        }
        if (s == Psp.Status.RECUSADO) {
            limite.liberar(pedido.orderId());
            if (pedido.reservado()) {
                estoque.liberar(pedido.orderId(), filho(pedido));
                pedido.reservado(false);
            }
            cancelarSemCobranca(pedido, "consulta do psp: nao pago");
            return;
        }
        if (s == Psp.Status.PIX_GERADO) {
            pedido.estado(EstadoPedido.AGUARDANDO_PAGAMENTO);
        }
    }

    private void retomarConfirmacao(Pedido pedido) {
        Estoque.Status s = consultarEstoque(pedido);
        if (s == Estoque.Status.DESCONHECIDO || s == Estoque.Status.SEM_RESPOSTA) {
            log(pedido, "job consultou a confirmacao e o status segue desconhecido; nao estorna");
            return;
        }
        aplicarConfirmacao(pedido, s);
    }

    private void retomarEstorno(Pedido pedido, long agoraMs) {
        Psp.Status s = consultarEstorno(pedido);
        if (s == Psp.Status.ESTORNADO) {
            pedido.estornoConfirmado(true);
            pedido.estado(EstadoPedido.CANCELADO_COM_ESTORNO);
            log(pedido, "job viu o estorno confirmado no psp; nao chama estornar de novo");
            return;
        }
        if (s == Psp.Status.DESCONHECIDO || s == Psp.Status.SEM_RESPOSTA) {
            log(pedido, "job consultou o estorno e o status segue desconhecido; nao chama estornar de novo");
            return;
        }
        if (s == Psp.Status.NAO_ESTORNADO) {
            long inicio = pedido.entrouEmRevisaoMs() == 0 ? agoraMs : pedido.entrouEmRevisaoMs();
            if (agoraMs - inicio >= PRAZO_REVISAO_MS) {
                if (pedido.estado() != EstadoPedido.ESTORNO_FALHOU) {
                    pedido.estado(EstadoPedido.ESTORNO_FALHOU);
                    pedido.alertaFinanceiro();
                    log(pedido, "24h sem estorno confirmado; alerta financeiro e o job segue consultando");
                }
                return;
            }
            if (pedido.estado() == EstadoPedido.ESTORNO_FALHOU) {
                return;
            }
            Psp.Status nova = psp.estornar(pedido.orderId(), filho(pedido));
            pedido.estornoSolicitado(true);
            if (nova == Psp.Status.ESTORNADO) {
                pedido.estornoConfirmado(true);
                pedido.estado(EstadoPedido.CANCELADO_COM_ESTORNO);
                return;
            }
            if (nova == Psp.Status.SEM_RESPOSTA) {
                consultarEstorno(pedido);
            }
        }
    }

    private Estoque.Status consultarEstoque(Pedido pedido) {
        pedido.maisConsultaEstoque();
        return estoque.consultar(pedido.orderId(), filho(pedido));
    }

    private Psp.Status consultarPagamento(Pedido pedido) {
        pedido.maisConsultaPagamento();
        return psp.consultarPagamento(pedido.orderId(), filho(pedido));
    }

    private Psp.Status consultarEstorno(Pedido pedido) {
        pedido.maisConsultaEstorno();
        return psp.consultarEstorno(pedido.orderId(), filho(pedido));
    }

    private void publicar(Pedido pedido, String tipo) {
        eventos.add(new EventoSaga(pedido.orderId(), pedido.orderId(), tipo, pedido.traceparent(),
                Particao.de(pedido.orderId(), PARTICICOES)));
    }

    private String filho(Pedido pedido) {
        return TraceContext.parse(pedido.traceparent()).filho().header();
    }

    private void log(Pedido pedido, String msg) {
        TraceContext span = TraceContext.parse(pedido.traceparent());
        logs.registrar("INFO", "checkout", pedido.traceId(), span.spanId(), pedido.orderId(),
                pedido.estado().name(), 0, msg, pedido.clienteRef());
    }

    private Resposta resposta(Pedido pedido, int http) {
        return new Resposta(http, corpoPublico(pedido), pedido);
    }

    public Map<String, Object> corpoPublico(Pedido pedido) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", pedido.orderId());
        m.put("estado", pedido.estado().name());
        m.put("traceId", pedido.traceId());
        if (pedido.estado() == EstadoPedido.AGUARDANDO_PAGAMENTO
                || pedido.estado() == EstadoPedido.PAGAMENTO_EM_VERIFICACAO) {
            m.put("valorDestePedidoCentavos", pedido.centavos());
        }
        return m;
    }

    static String resultadoDe(EstadoPedido estado) {
        return switch (estado) {
            case CONCLUIDO, PAGO, PENDENTE -> "ok";
            case CANCELADO_SEM_COBRANCA -> "sem_cobranca";
            case CANCELADO_COM_ESTORNO, ESTORNANDO -> "estornado";
            case ESTORNO_FALHOU -> "estorno_falhou";
            case AGUARDANDO_PAGAMENTO -> "pix_pendente";
            case ESTOQUE_EM_VERIFICACAO, PAGAMENTO_EM_VERIFICACAO,
                    CONFIRMACAO_EM_VERIFICACAO, ESTORNO_EM_REVISAO -> "verificacao";
        };
    }

    public record Resposta(int http, Map<String, Object> corpo, Pedido pedido) {}
}
