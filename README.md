# ada-mod2

Repositório das aulas do **Módulo 2 da Ada (PE-JV-003)**. Todas as aulas contam uma só história, e cada aula é um capítulo.

## A história

Temos uma **loja online**. Na hora de pagar, a API de **Checkout** (o "caixa" do site) não cobra o cartão sozinha: ela chama um **parceiro de pagamento externo** (ex.: Cielo, Stripe). Esse parceiro é instável: às vezes demora, às vezes devolve erro, às vezes derruba a conexão. E a cliente, quando a tela trava, aperta "pagar" de novo.

A meta é dura: **10 mil usuários ao mesmo tempo**. Depois vem a **Black Friday**: centenas de pessoas disputando as últimas unidades do mesmo produto em promoção.
