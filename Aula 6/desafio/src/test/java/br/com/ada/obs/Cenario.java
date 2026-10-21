package br.com.ada.obs;

import java.util.concurrent.atomic.AtomicLong;

final class Cenario {
    final EstoqueScript estoque = new EstoqueScript();
    final PspScript psp = new PspScript();
    final LimitePendente limite = new LimitePendente(10_000, 50_000);
    final LogEstruturado logs = new LogEstruturado("chave-teste");
    final Histograma histograma = new Histograma();
    final AtomicLong relogio = new AtomicLong(1_000);
    final CheckoutService service = new CheckoutService(
            estoque, psp, limite, logs, histograma, "v2", "segredo-teste", relogio::get);

    CheckoutService.Resposta finalizar(String orderId) {
        return service.finalizar(orderId, "cli-771", 8_000, "FONE-BF", TraceContext.criar().header());
    }
}
