# Template de System Design: Aula 06 - Observabilidade & Diagnóstico em Produção

> **Cenário:** 5.000 pedidos/s, versão nova em canary (uma fatia do tráfego). Média 120 ms, CPU abaixo de 40%, zero HTTP 500. A conversão caiu: a tela carrega até o aplicativo desistir. A imagem é Distroless (sem shell e sem SSH). O log não tem um id comum. Dado de cliente segue a LGPD (Lei 13.709/2018).

Contas: [anexo](anexo-detalhes.md). Estados: [apêndice](../Aula%204/apendice-maquina-de-estados.md).

## 1. Logs e Correlação

- **Campos do Log:**
  > A escolha é uma linha JSON igual nos três serviços.
  > `ts` ordena. `level` separa INFO de ERROR. `service` diz quem escreveu. `trace_id` (32 hex) é a requisição. `span_id` (16 hex) é este salto. `order_id` é o pedido. `estado` é o passo. `duracao_ms` é o tempo. `cliente_ref` é o pseudônimo. `msg` é a frase.
  > Exemplo: `order_id=48213` devolve o `trace_id`, `duracao_ms=2000` e `estado=PAGAMENTO_EM_VERIFICACAO`.
  > 100% no erro, no HTTP 5xx, na verificação (`ESTOQUE_EM_VERIFICACAO`, `PAGAMENTO_EM_VERIFICACAO`, `CONFIRMACAO_EM_VERIFICACAO`) e em `ESTORNANDO`, `ESTORNO_EM_REVISAO`, `ESTORNO_FALHOU`, `CANCELADO_SEM_COBRANCA`, `CANCELADO_COM_ESTORNO`.
  > 5% só no caminho feliz: `PENDENTE`, `AGUARDANDO_PAGAMENTO`, `PAGO`, `CONCLUIDO`.
  > Quatro linhas a 5.000/s seriam 20.000 linhas/s. Se todo pedido fosse feliz, 5% seriam cerca de 1.000 linhas/s. Esse 1.000 é só essa fatia. O restante soma por cima. [Logs](anexo-detalhes.md#logs).
- **O Caminho do Contexto:**
  > O Checkout manda o `traceparent` (`00-<trace 32 hex>-<span 16 hex>-01`) ao Estoque e ao Pagamento. Os dois são irmãos. Id só de zeros é rejeitado.
  > O comando da saga vai pelo Kafka, chave `orderId`, com esse cabeçalho. O MDC (o mapa da thread) não atravessa o consumidor.
  > Dinheiro capturado em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO`. Sem captura, o estado não muda.
  > Webhook sem o nosso trace reusa o `trace_id` do pedido. HMAC errado responde 401. HMAC: uma assinatura. Sem a chave, não dá para forjar. [Propagação](anexo-detalhes.md#propagacao).
- **LGPD nos Logs:**
  > Fora de log, trace e label: CPF, nome, e-mail, telefone, endereço, cartão, CVV e IP completo. Corpo com `cpf`, `email` ou `cartao` recebe HTTP 400.
  > `order_id` entra, com acesso restrito. Ainda é dado pessoal. `cliente_ref` é HMAC-SHA256 do CPF, 12 hex, chave no cofre. Com a chave a pessoa volta, então segue dado pessoal (art. 12).
  > O art. 13, §4º só define pseudonimização, e é norma de pesquisa em saúde.
  > Prazos desta entrega (art. 6º, III): trace 7 dias, log com `order_id` 15 dias, métrica agregada 30 dias. A lei não fixa esses dias.
  > Engenharia vê o painel sem `order_id`. O plantão lê a linha com login. [LGPD](anexo-detalhes.md#lgpd).

## 2. Métricas

- **Visão do Cliente (RED):**
  > A escolha é RED: taxa, erro e duração, por `finalizar`, `reservar`, `cobrar`, `estornar` e `confirmar`. RED: o que o cliente sentiu.
  > Erro: `resultado` em `verificacao` ou `estorno_falhou`. `ESTORNO_EM_REVISAO` conta como `verificacao`. `sem_cobranca` fica fora. Timeout, desistência e HTTP 5xx entram em `http_server_requests_seconds`. O painel soma as duas fontes.
  > O pedido `PENDENTE` é caminho feliz: a reserva ainda não respondeu, nada foi cobrado. A métrica é `ok`.
  > A duração é um histograma (contador por faixa de tempo). A média só soma o que já terminou.
  > Conta: 9.800 × 0,07 s + 200 × 2,57 s dá média de 120 ms e p99 de 2,57 s. Alerta de média (300 ms) em silêncio. Alerta de p99 (800 ms) dispara.
  > Little: 4.900 × 0,120 s = 588 e 100 × 8 s = 800. A soma, 1.388, passa do dobro da base (1.176). Os 8 s são essa conta e o sono do teste.
  > O 7 s é o p99 de uma réplica do canary. O 6,0 s é a estimativa por balde desse conjunto. O 238 ms é a média das dez réplicas.
  > Conversão: só quem entrou há mais de 15 min, terminais no denominador, limiar = taxa da hora anterior. A aula não fixa uma conversão "normal". [RED](anexo-detalhes.md#red).
- **Visão dos Recursos (USE):**
  > A escolha é USE no pool do Postgres, no pool HTTP, no lag da saga e no Pix em `AGUARDANDO_PAGAMENTO`. USE: uso, saturação e erro. O sinal antes do 500 é a fila, não a CPU.
  > Exemplo: pool 10/10, fila 200, CPU 15%, HTTP 500 = 0. Fila zero: alerta em silêncio. Se `finalizar` responde 202 em 120 ms e o consumidor para, sobem o lag e `checkout_pedidos_em_aberto`.
  > Teto do Pix: 1,5 vez a hora anterior. R$ 2.000 e R$ 8.000.000 no `application.yml` são cenário de teste. O teste usa R$ 100 e dois pedidos de R$ 80. [USE](anexo-detalhes.md#use).
- **Labels:**
  > Quatro labels: `servico` (3), `operacao` (5), `resultado` (6: `ok`, `sem_cobranca`, `estornado`, `estorno_falhou`, `pix_pendente`, `verificacao`) e `versao` (2).
  > 3 × 5 × 6 × 2 = 180. 180 × 11 = 1.980 séries. O 11 são 9 baldes (0,05, 0,1, 0,25, 0,5, 1, 2, 4, 8, +Inf) mais `_sum` e `_count`.
  > `PAGO` e `PENDENTE` contam como `ok`. `ESTORNANDO` conta como `estornado` (fora da taxa no prazo). `ESTORNO_EM_REVISAO` entra na taxa, com `estorno_falhou`.
  > Proibidos: `order_id`, `trace_id`, `user_id`, `cpf`, `email`, `sku_id`. Sem `versao`, a média dos p99 (790 ms) esconde o canary.
  > `order_id` num minuto: 5.000/s × 60 = 300.000 × 11 = 3,3 milhões de séries. [Labels](anexo-detalhes.md#labels).

## 3. Traces e Amostragem

- **A Escolha:** ( ) Head Sampling  (X) Tail Sampling  ( ) Combinação das duas
- **Justificativa:**
  > A escolha é tail no collector: decide depois de ver a duração. O SDK usa `AlwaysOn`. Head jogaria fora o lento antes da duração existir.
  > Regra: 100% com erro, 100% acima de 800 ms, 100% em verificação, `ESTORNO_EM_REVISAO` ou `ESTORNO_FALHOU`, e 1% do resto pelo trace-id.
  > Em produção, `finalizar` devolve 202 e o comando segue por Kafka, via outbox (tabela de saída, na mesma transação).
  > `decision_wait` de 15 s cobre o 202 e o consumidor desse `trace_id`. Conta: 5.000 × 15 = 75.000 traces. Pix (15 min), `prazo_passo` de 30 s e o estorno são outros traces, ligados por `order_id`.
  > À parte: 2 s é a resposta do parceiro. 3 s é o timeout HTTP do estorno. O 5 s é a segunda espera do estorno, não uma chamada do Checkout ao Pagamento.
  > No laboratório, `finalizar` chama reservar, cobrar e confirmar no mesmo processo. Isso simula a borda. Não é o transporte de produção.
  > `Amostrador.tail` não lê o estado. O chamador passa `erro=true` para verificação, `ESTORNO_FALHOU` e `ESTORNO_EM_REVISAO`.
  > Cada span conta 1 KB. Seis spans a 5.000/s dariam 30 MB/s. Com a regra, cerca de 150 traces/s. A janela cabe em cerca de 450 MB. O collector pede 2 GB. [Traces](anexo-detalhes.md#traces).

## 4. Estratégia de Testes

- **Qual a nossa estratégia para testar isso e garantir que funciona?**
  > A mesma régua nos quatro níveis: o p99 ou o trabalho em andamento dispara. A média (300 ms) fica em silêncio.
  > O k6 só mede carga. Os 2% de estoque dormindo 8 s vêm dos testes Java dos níveis 1 a 3.
  > Sem saldo: `CANCELADO_SEM_COBRANCA`. `PagamentoAprovado` sem captura não muda o estado. Com captura, vai para `ESTORNANDO`.
  > O pedido 48213 chega a `CONCLUIDO`. CPF no corpo é recusado. Webhook sem assinatura volta 401. O trace lento tem o mesmo `trace_id` nos três. `mvn verify` roda 27 testes.
  > Causa desconhecida: abortar o canary e, acima do limiar, 503 com a mesma chave. Pedido em verificação não é cancelado. [Testes](anexo-detalhes.md#testes).
