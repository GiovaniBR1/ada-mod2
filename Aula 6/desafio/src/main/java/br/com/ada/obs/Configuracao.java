package br.com.ada.obs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class Configuracao {

    @Bean
    Estoque estoque() { return new EstoqueLocal(); }

    @Bean
    Psp psp() { return new PspLocal(); }

    @Bean
    LimitePendente limite(
            @Value("${checkout.teto-pendente-cliente-centavos}") long cliente,
            @Value("${checkout.teto-pendente-global-centavos}") long global) {
        return new LimitePendente(cliente, global);
    }

    @Bean
    LogEstruturado logs() { return new LogEstruturado("chave-hmac-do-cliente"); }

    @Bean
    Histograma histograma() { return new Histograma(); }

    @Bean
    CheckoutService checkout(Estoque estoque, Psp psp, LimitePendente limite, LogEstruturado logs, Histograma histograma,
                             @Value("${checkout.versao}") String versao,
                             @Value("${checkout.webhook-segredo}") String segredo) {
        return new CheckoutService(estoque, psp, limite, logs, histograma, versao, segredo, System::currentTimeMillis);
    }
}
