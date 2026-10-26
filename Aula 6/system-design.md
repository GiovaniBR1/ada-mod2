# System Design: Aula 06 - Observabilidade & Diagnóstico em Produção

> **Cenário:** 5.000 pedidos/s, versão nova em canary (uma fatia do tráfego). Média 120 ms, CPU abaixo de 40%, zero HTTP 500. A conversão caiu: a tela carrega até o aplicativo desistir. A imagem é Distroless (sem shell e sem SSH). O log não tem um id comum. Dado de cliente segue a LGPD (Lei 13.709/2018).

Contas: [anexo](anexo-detalhes.md). Estados: [apêndice](../Aula%204/apendice-maquina-de-estados.md).

## 1. Logs e Correlação

- **Campos do Log:**
  > A escolha é uma linha JSON igual nos três serviços.
  > `ts` ordena. `level` separa INFO de ERROR. `service` diz quem escreveu. `trace_id` (32 hex) é a requisição. `span_id` (16 hex) é este salto. `order_id` é o pedido. `estado` é o passo. `duracao_ms` é o tempo. `cliente_ref` é o pseudônimo. `msg` é a frase.
  > Exemplo: `order_id=48213` devolve o `trace_id`, `duracao_ms=2000` e `estado=PAGAMENTO_EM_VERIFICACAO`.
  > 100% no erro, no HTTP 5xx, na verificação (`ESTOQUE_EM_VERIFICACAO`, `PAGAMENTO_EM_VERIFICACAO`, `CONFIRMACAO_EM_VERIFICACAO`) e em `ESTORNANDO`, `ESTORNO_EM_REVISAO`, `ESTORNO_FALHOU`, `CANCELADO_SEM_COBRANCA`, `CANCELADO_COM_ESTORNO`.
  > 5% só no caminho feliz: `PENDENTE`, `AGUARDANDO_PAGAMENTO`, `PAGO`, `CONCLUIDO`.
  > Uma linha por estado do caminho feliz (`PENDENTE`, `AGUARDANDO_PAGAMENTO`, `PAGO`, `CONCLUIDO`) a 5.000 pedidos/s seriam 20.000 linhas/s. Se todo pedido fosse feliz, 5% seriam cerca de 1.000 linhas/s. Esse 1.000 linhas/s é só essa fatia. O restante soma por cima. [Logs](anexo-detalhes.md#logs).
- **O Caminho do Contexto:**
  > O Checkout manda o `traceparent` (`00-<trace 32 hex>-<span 16 hex>-01`) ao Estoque e ao Pagamento. Os dois são irmãos. Id só de zeros é rejeitado.
  > O comando nasce na outbox e segue pelo Kafka, chave `orderId`, com esse cabeçalho. O MDC (o mapa da thread) não atravessa o consumidor.
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
  > Erro: `resultado` em `verificacao` ou `estorno_falhou`. `ESTORNO_EM_REVISAO` usa `verificacao` e entra na taxa, como `estorno_falhou`. `sem_cobranca` fica fora.
  > `AGUARDANDO_PAGAMENTO` usa `pix_pendente` e fica fora da taxa. `PENDENTE`, `PAGO` e `CONCLUIDO` usam `ok`.
  > `CANCELADO_COM_ESTORNO` e `ESTORNANDO` usam `estornado`, fora da taxa no prazo. Duração é histograma (contagem por faixa). p99: 99 em 100 foram mais rápidos.
  > Exemplo 1: 9.800 × 0,07 s + 200 × 2,57 s dá média de 120 ms e p99 de 2,57 s. Alerta de média (300 ms) cala. O de p99 (800 ms) dispara.
  > Exemplo 2, em andamento: 4.900 × 0,120 s = 588, mais 100 × 8 s = 800, total 1.388. O alerta dispara acima de 1.176, o dobro dos 588.
  > Esses 8 s são a conta de Little e o sono do teste. Não são os 7 s do exemplo 3.
  > Exemplo 3: nove réplicas a 100 ms. Uma tem 200 de 1.000 chamadas a 7 s, e o p99 dela é 7 s. A média dos dez p99 é (9 × 100 ms + 7.000 ms) / 10 = 790 ms.
  > Balde é a faixa do histograma. No conjunto, 200 chamadas caem na faixa de 4 s a 8 s. A posição 9.900 entra 100 delas.
  > A conta é 4 + (8 − 4) × 100 / 200 = 6,0 s. A média é (9.800 × 0,1 s + 200 × 7 s) / 10.000 = 238 ms.
  > Terminal: estado em que o cliente já tem desfecho. Conversão: o denominador é a coorte com entrada há mais de 15 min, todos.
  > O numerador é quem chegou a desfecho: `CONCLUIDO`, `CANCELADO_SEM_COBRANCA` ou `CANCELADO_COM_ESTORNO`. Preso fora de terminal baixa a taxa.
  > Limiar = taxa da hora anterior. [RED](anexo-detalhes.md#red).
- **Visão dos Recursos (USE):**
  > A escolha é USE no pool do Postgres, no pool HTTP, no lag da saga e no Pix em `AGUARDANDO_PAGAMENTO`. USE: uso, saturação e erro. O sinal antes do 500 é a fila, não a CPU.
  > Exemplo: pool 10/10, fila 200, CPU 15%, HTTP 500 = 0. Fila zero: alerta em silêncio. Se `finalizar` responde 202 em 120 ms e o consumidor para, sobem o lag e `checkout_pedidos_em_aberto`.
  > Teto do Pix: 1,5 vez a hora anterior. R$ 2.000 e R$ 8.000.000 no `application.yml` são cenário de teste. O teste usa R$ 100 e dois pedidos de R$ 80. [USE](anexo-detalhes.md#use).
- **Labels:**
  > Quatro labels: `servico` (3), `operacao` (5), `resultado` (6: `ok`, `sem_cobranca`, `estornado`, `estorno_falhou`, `pix_pendente`, `verificacao`) e `versao` (2).
  > 3 × 5 × 6 × 2 = 180 combinações de label. 180 × 11 = 1.980 séries. O 11 são 9 baldes (0,05, 0,1, 0,25, 0,5, 1, 2, 4, 8, +Inf) mais `_sum` e `_count`.
  > Proibidos: `order_id`, `trace_id`, `user_id`, `cpf`, `email`, `sku_id`. Sem `versao`, a média dos p99 (790 ms) esconde o canary.
  > `order_id` num minuto: 5.000/s × 60 = 300.000 ids. 300.000 × 11 = 3,3 milhões de séries. [Labels](anexo-detalhes.md#labels).

## 3. Traces e Amostragem

- **A Escolha:** ( ) Head Sampling  (X) Tail Sampling  ( ) Combinação das duas
- **Justificativa:**
  > A escolha é tail no collector (quem recebe o trace e decide se guarda): decide depois da duração.
  > O SDK (biblioteca no processo) usa `AlwaysOn`: manda todo trace. Head cortaria o lento antes da duração existir.
  > Regra: 100% com erro, 100% acima de 800 ms, 100% em verificação, `ESTORNO_EM_REVISAO` ou `ESTORNO_FALHOU`, e 1% do resto pelo trace-id.
  > `decision_wait` é a espera do collector. Os 15 s valem só para o `finalizar`: o 202 e o consumidor desse `trace_id`. A conta é 5.000 × 15 s = 75.000 traces em voo.
  > Ao entrar em verificação ou em estado de erro, o trace é guardado na hora, sem esperar o fim. O chamador passa `erro=true`.
  > Assim o passo de 30 s do `PAGO` e o Pix de 15 min não precisam de outra janela. Segurar o Pix 15 min seriam 5.000 × 900 s = 4,5 milhões de traces.
  > Com a regra, 100 traces/s lentos mais 1% de 4.900 (= 49) dão cerca de 150 traces/s. Seis spans de 1 KB a 5.000/s dariam 30 MB/s. A janela de 15 s cabe em cerca de 450 MB. O collector pede 2 GB.
  > À parte: 2 s é a resposta do parceiro. 3 s é o timeout HTTP do estorno. O 5 s é a segunda espera do estorno.
  > No laboratório, `finalizar` chama reservar, cobrar e confirmar no mesmo processo. `Amostrador.tail` não lê o estado. [Traces](anexo-detalhes.md#traces).

## 4. Estratégia de Testes

- **Qual a nossa estratégia para testar isso e garantir que funciona?**
  > A mesma régua nos quatro níveis: o p99 ou o trabalho em andamento dispara. A média (300 ms) fica em silêncio.
  > O k6 (ferramenta de carga) só mede carga. Os 2% de estoque dormindo 8 s vêm dos testes Java dos níveis 1 a 3.
  > Sem saldo: `CANCELADO_SEM_COBRANCA`. `PagamentoAprovado` sem captura não muda o estado.
  > O pedido 48213 chega a `CONCLUIDO`. CPF no corpo é recusado. Webhook sem assinatura volta 401. O trace lento tem o mesmo `trace_id` nos três. `mvn verify` roda 27 testes.
  > Causa desconhecida: abortar o canary e, acima do limiar, 503 com a mesma chave. Pedido em verificação não é cancelado. [Testes](anexo-detalhes.md#testes).
