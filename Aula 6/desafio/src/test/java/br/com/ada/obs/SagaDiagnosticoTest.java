package br.com.ada.obs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SagaDiagnosticoTest {

    @Test
    void semSaldoCancelaSemCobrancaENaoConfirma() {
        Cenario c = new Cenario();
        int[] emAndamento = {-1};
        c.estoque.aoReservar(() -> emAndamento[0] = c.service.httpEmAndamento());
        c.estoque.reservar(Estoque.Status.SEM_SALDO);

        var r = c.finalizar("48213");

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.CANCELADO_SEM_COBRANCA);
        assertThat(r.pedido().estado()).isNotEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(c.psp.chamadasCobrar()).isZero();
        assertThat(c.psp.chamadasEstornar()).isZero();
        assertThat(c.estoque.chamadasLiberar()).isZero();
        assertThat(r.pedido().consultasEstoque()).isZero();
        assertThat(emAndamento[0]).isEqualTo(1);
        assertThat(c.service.httpEmAndamento()).isZero();
        assertThat(c.service.aplicarEvento("48213", "PagamentoConfirmado")).isFalse();
        assertThat(c.service.pedido("48213").estado()).isEqualTo(EstadoPedido.CANCELADO_SEM_COBRANCA);
        assertThat(c.psp.chamadasCobrar()).isZero();
    }

    @Test
    void estoqueMudoComStatusDesconhecidoNaoCobraNemCancela() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.SEM_RESPOSTA).consultar(Estoque.Status.DESCONHECIDO, Estoque.Status.DESCONHECIDO);

        var r = c.finalizar("48213");
        c.service.reconciliar(c.relogio.get());

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.ESTOQUE_EM_VERIFICACAO);
        assertThat(r.pedido().consultasEstoque()).isEqualTo(2);
        assertThat(c.psp.chamadasCobrar()).isZero();
        assertThat(c.psp.chamadasEstornar()).isZero();
        assertThat(r.pedido().estado()).isNotIn(EstadoPedido.CONFIRMADO, EstadoPedido.CANCELADO_SEM_COBRANCA, EstadoPedido.CANCELADO_COM_ESTORNO);
        assertThat(c.logs.linhas()).anyMatch(l -> l.contains("consultando status antes de decidir") && l.contains(r.pedido().traceId()));
        assertThat(TraceContext.parse(c.estoque.ultimoTraceparent()).traceId()).isEqualTo(r.pedido().traceId());
    }

    @Test
    void estoqueMudoMasConsultaMostraQueNaoReservouCancelaSemCobranca() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.SEM_RESPOSTA).consultar(Estoque.Status.NAO_RESERVADO);

        var r = c.finalizar("48213");

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.CANCELADO_SEM_COBRANCA);
        assertThat(r.pedido().consultasEstoque()).isEqualTo(1);
        assertThat(c.psp.chamadasCobrar()).isZero();
        assertThat(c.psp.chamadasEstornar()).isZero();
        assertThat(c.estoque.chamadasLiberar()).isZero();
        assertThat(r.pedido().estado()).isNotEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void pspMudoComStatusDesconhecidoNaoEstornaNemCancela() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO);
        c.psp.cobrar(Psp.Status.SEM_RESPOSTA).consultarPagamento(Psp.Status.DESCONHECIDO, Psp.Status.DESCONHECIDO);

        var r = c.finalizar("48213");
        c.service.reconciliar(c.relogio.get());

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.PAGAMENTO_EM_VERIFICACAO);
        assertThat(r.pedido().consultasPagamento()).isEqualTo(2);
        assertThat(c.psp.chamadasEstornar()).isZero();
        assertThat(c.estoque.chamadasLiberar()).isZero();
        assertThat(r.pedido().estado()).isNotIn(EstadoPedido.CANCELADO_SEM_COBRANCA, EstadoPedido.CANCELADO_COM_ESTORNO, EstadoPedido.CONFIRMADO);
        assertThat(r.corpo()).containsEntry("valorDestePedidoCentavos", 8_000L);
        assertThat(r.corpo()).doesNotContainKey("somaPendenteDoCliente").doesNotContainKey("cpf");
    }

    @Test
    void pspMudoMasConsultaMostraRecusadoCancelaSemCobrancaELiberaEstoque() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO);
        c.psp.cobrar(Psp.Status.SEM_RESPOSTA).consultarPagamento(Psp.Status.RECUSADO);

        var r = c.finalizar("48213");

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.CANCELADO_SEM_COBRANCA);
        assertThat(r.pedido().consultasPagamento()).isEqualTo(1);
        assertThat(c.estoque.chamadasLiberar()).isEqualTo(1);
        assertThat(c.psp.chamadasEstornar()).isZero();
        assertThat(r.pedido().estado()).isNotEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void pspMudoMasConsultaMostraPagoEEstoqueConfirma() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO).confirmar(Estoque.Status.CONFIRMADO);
        c.psp.cobrar(Psp.Status.SEM_RESPOSTA).consultarPagamento(Psp.Status.PAGO);

        var r = c.finalizar("48213");

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(r.pedido().reservado()).isTrue();
        assertThat(r.pedido().pagamentoCapturado()).isTrue();
        assertThat(r.pedido().consultasPagamento()).isEqualTo(1);
        assertThat(c.psp.chamadasEstornar()).isZero();
        assertThat(c.service.finalizar("48213", "cli-771", 8_000, "FONE-BF", null).pedido().estado())
                .isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(c.psp.chamadasCobrar()).isEqualTo(1);
    }

    @Test
    void reservaExpiraEstornaUmaVezMesmoComWebhookEEventoRepetidos() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO).confirmar(Estoque.Status.RESERVA_EXPIROU);
        c.psp.cobrar(Psp.Status.PAGO).estornar(Psp.Status.ESTORNADO);

        var r = c.finalizar("48213");
        String corpo = r.pedido().pagamentoId() + "|48213|ESTORNADO";
        String assinatura = WebhookAssinatura.assinar(c.service.segredoWebhook(), corpo);

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.CANCELADO_COM_ESTORNO);
        assertThat(c.psp.chamadasEstornar()).isEqualTo(1);
        assertThat(c.service.receberWebhook(corpo, assinatura).http()).isEqualTo(200);
        assertThat(c.service.receberWebhook(corpo, assinatura).corpo()).containsEntry("resultado", "repetido");
        assertThat(c.service.aplicarEvento("48213", "ReservaExpirou")).isFalse();
        assertThat(c.service.aplicarEvento("48213", "ReservaExpirou")).isFalse();
        assertThat(c.psp.chamadasEstornar()).isEqualTo(1);
        assertThat(c.service.pedido("48213").estado()).isEqualTo(EstadoPedido.CANCELADO_COM_ESTORNO);
        assertThat(c.service.pedido("48213").estado()).isNotEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void confirmacaoMudaConsultaAntesDeEstornar() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO).confirmar(Estoque.Status.SEM_RESPOSTA).consultar(Estoque.Status.RESERVA_EXPIROU);
        c.psp.cobrar(Psp.Status.PAGO).estornar(Psp.Status.ESTORNADO);

        var r = c.finalizar("48213");

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.CANCELADO_COM_ESTORNO);
        assertThat(r.pedido().consultasEstoque()).isEqualTo(1);
        assertThat(c.psp.chamadasEstornar()).isEqualTo(1);
        assertThat(c.logs.linhas()).anyMatch(l -> l.contains("consultando status antes de decidir"));
    }

    @Test
    void confirmacaoDesconhecidaNaoEstornaNemConfirma() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO).confirmar(Estoque.Status.SEM_RESPOSTA)
                .consultar(Estoque.Status.DESCONHECIDO, Estoque.Status.DESCONHECIDO);
        c.psp.cobrar(Psp.Status.PAGO);

        var r = c.finalizar("48213");
        c.service.reconciliar(c.relogio.get());

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.CONFIRMACAO_EM_VERIFICACAO);
        assertThat(c.psp.chamadasEstornar()).isZero();
        assertThat(r.pedido().estado()).isNotEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(r.pedido().pagamentoCapturado()).isTrue();
    }

    @Test
    void estornoSemRespostaFicaEmRevisaoEJobConcluiSemNovaChamada() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO).confirmar(Estoque.Status.RESERVA_EXPIROU);
        c.psp.cobrar(Psp.Status.PAGO).estornar(Psp.Status.SEM_RESPOSTA)
                .consultarEstorno(Psp.Status.DESCONHECIDO, Psp.Status.ESTORNADO);

        var r = c.finalizar("48213");
        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.ESTORNO_EM_REVISAO);
        assertThat(c.psp.chamadasEstornar()).isEqualTo(1);
        assertThat(r.pedido().consultasEstorno()).isEqualTo(1);

        c.service.reconciliar(c.relogio.get());

        assertThat(c.service.pedido("48213").estado()).isEqualTo(EstadoPedido.CANCELADO_COM_ESTORNO);
        assertThat(c.psp.chamadasEstornar()).isEqualTo(1);
    }

    @Test
    void jobNaoEstornaEnquantoStatusDoPspEDesconhecido() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO).confirmar(Estoque.Status.RESERVA_EXPIROU);
        c.psp.cobrar(Psp.Status.PAGO).estornar(Psp.Status.SEM_RESPOSTA)
                .consultarEstorno(Psp.Status.DESCONHECIDO, Psp.Status.DESCONHECIDO);

        c.finalizar("48213");
        c.service.reconciliar(c.relogio.get());

        assertThat(c.service.pedido("48213").estado()).isEqualTo(EstadoPedido.ESTORNO_EM_REVISAO);
        assertThat(c.psp.chamadasEstornar()).isEqualTo(1);
        assertThat(c.service.pedido("48213").estado()).isNotEqualTo(EstadoPedido.CANCELADO_COM_ESTORNO);
    }

    @Test
    void dentroDoPrazoJobTentaOEstornoUmaVez() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO).confirmar(Estoque.Status.RESERVA_EXPIROU);
        c.psp.cobrar(Psp.Status.PAGO).estornar(Psp.Status.NAO_ESTORNADO, Psp.Status.ESTORNADO)
                .consultarEstorno(Psp.Status.NAO_ESTORNADO);

        c.finalizar("48213");
        assertThat(c.psp.chamadasEstornar()).isEqualTo(1);
        assertThat(c.service.pedido("48213").consultasEstorno()).isZero();

        c.relogio.addAndGet(60_000);
        c.service.reconciliar(c.relogio.get());

        assertThat(c.psp.chamadasEstornar()).isEqualTo(2);
        assertThat(c.service.pedido("48213").estado()).isEqualTo(EstadoPedido.CANCELADO_COM_ESTORNO);
    }

    @Test
    void apos24hSemEstornoAbreAlertaENoDiaSeguinteAConsultaAindaFecha() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO).confirmar(Estoque.Status.RESERVA_EXPIROU);
        c.psp.cobrar(Psp.Status.PAGO).estornar(Psp.Status.SEM_RESPOSTA)
                .consultarEstorno(Psp.Status.DESCONHECIDO, Psp.Status.NAO_ESTORNADO, Psp.Status.ESTORNADO);

        c.finalizar("48213");
        long inicio = c.service.pedido("48213").entrouEmRevisaoMs();
        c.relogio.set(inicio + CheckoutService.PRAZO_REVISAO_MS + 1);
        c.service.reconciliar(c.relogio.get());

        assertThat(c.service.pedido("48213").estado()).isEqualTo(EstadoPedido.ESTORNO_FALHOU);
        assertThat(c.service.pedido("48213").alertasFinanceiros()).isEqualTo(1);
        assertThat(c.psp.chamadasEstornar()).isEqualTo(1);

        c.relogio.addAndGet(CheckoutService.PRAZO_REVISAO_MS);
        c.service.reconciliar(c.relogio.get());

        assertThat(c.service.pedido("48213").estado()).isEqualTo(EstadoPedido.CANCELADO_COM_ESTORNO);
        assertThat(c.psp.chamadasEstornar()).isEqualTo(1);
    }

    @Test
    void tetoDePendenteBloqueiaNovaCobrancaERespostaNaoExpoeASoma() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO, Estoque.Status.RESERVADO);
        c.psp.cobrar(Psp.Status.PIX_GERADO);

        var primeiro = c.finalizar("48213");
        var segundo = c.finalizar("48214");

        assertThat(primeiro.pedido().estado()).isEqualTo(EstadoPedido.AGUARDANDO_PIX);
        assertThat(primeiro.corpo()).containsEntry("valorDestePedidoCentavos", 8_000L);
        assertThat(primeiro.corpo()).doesNotContainKey("somaPendenteDoCliente").doesNotContainKey("cpf");
        assertThat(c.limite.somaDoCliente("cli-771")).isEqualTo(8_000L);
        assertThat(segundo.pedido().estado()).isEqualTo(EstadoPedido.CANCELADO_SEM_COBRANCA);
        assertThat(c.psp.chamadasCobrar()).isEqualTo(1);
        assertThat(c.psp.chamadasEstornar()).isZero();
        assertThat(c.estoque.chamadasLiberar()).isEqualTo(1);
        assertThat(segundo.pedido().estado()).isNotEqualTo(EstadoPedido.CONFIRMADO);
    }

    @Test
    void webhookPagoConfirmaUmaVezEAssinaturaErradaNaoMudaNada() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.RESERVADO).confirmar(Estoque.Status.CONFIRMADO);
        c.psp.cobrar(Psp.Status.PIX_GERADO);

        var r = c.finalizar("48213");
        String corpo = r.pedido().pagamentoId() + "|48213|PAGO";
        assertThat(c.service.receberWebhook(corpo, "assinatura-errada").http()).isEqualTo(401);
        assertThat(c.service.pedido("48213").estado()).isEqualTo(EstadoPedido.AGUARDANDO_PIX);
        assertThat(c.estoque.chamadasConfirmar()).isZero();

        String assinatura = WebhookAssinatura.assinar(c.service.segredoWebhook(), corpo);
        assertThat(c.service.receberWebhook(corpo, assinatura).pedido().estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(c.service.receberWebhook(corpo, assinatura).corpo()).containsEntry("resultado", "repetido");
        assertThat(c.estoque.chamadasConfirmar()).isEqualTo(1);
        assertThat(c.psp.chamadasCobrar()).isEqualTo(1);
    }

    @Test
    void eventosUsamOrderIdComoChaveECarregamOTrace() {
        Cenario c = new Cenario();
        c.estoque.reservar(Estoque.Status.SEM_SALDO);
        String entrada = TraceContext.criar().header();
        var r = c.service.finalizar("48213", "cli-771", 8_000, "FONE-BF", entrada);

        assertThat(Particao.de("48213", 12)).isNotEqualTo(Particao.de("FONE-BF", 12));
        EventoSaga evento = c.service.eventos().get(0);
        assertThat(evento.chaveParticao()).isEqualTo("48213");
        assertThat(evento.particao()).isEqualTo(Particao.de("48213", 12));
        assertThat(evento.particao()).isNotEqualTo(Particao.de("FONE-BF", 12));
        assertThat(TraceContext.parse(evento.traceparent()).traceId()).isEqualTo(TraceContext.parse(entrada).traceId());
        assertThat(r.pedido().traceId()).isEqualTo(TraceContext.parse(entrada).traceId());
        assertThat(c.histograma.prometheus("checkout_operacao_duracao_seconds"))
                .doesNotContain("order_id").doesNotContain("cpf").contains("resultado=\"sem_cobranca\"");
    }
}
