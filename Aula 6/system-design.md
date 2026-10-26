# Template de System Design: Aula 06 - Observabilidade & Diagnóstico em Produção

> **Contexto do Cenário: O Checkout que Trava sem Dar Erro**
>
> Sua equipe responde pelo serviço de **Checkout** do marketplace, que chama **Pagamento** e **Estoque**. Os três rodam em imagens *Distroless*.
>
> 1. **O Incidente:** Na abertura da Black Friday, com **5.000 TPS**, uma versão nova do Checkout entrou em *Canary*. Minutos depois, os painéis continuavam verdes, com latência média de 120 ms, CPU abaixo de 40% e nenhum pico de HTTP 500.
> 2. **O Impacto:** A conversão despencou. Milhares de reclamações chegaram ao suporte, todas com a mesma cena. O cliente clica em "Finalizar Pedido", a tela fica carregando e o aplicativo desiste por timeout.
> 3. **As Restrições:** A imagem não tem `sh` e não há SSH nos servidores. Cada serviço escreve logs em texto livre, do seu jeito, sem um identificador que ligue a chamada do Checkout à chamada que ele fez ao Pagamento. E dado de cliente que vai parar em log continua sujeito à LGPD.
>
> **Instruções:** Responda às decisões arquiteturais abaixo fundamentando suas escolhas estritamente com base nos requisitos e restrições deste cenário. Documente suas decisões no repositório contínuo do seu projeto através de commits atômicos e bem descritivos.

Contas, PromQL, tabelas e o roteiro de testes estão no [anexo](anexo-detalhes.md). Os nomes de estado são os do [apêndice da Aula 4](../Aula%204/apendice-maquina-de-estados.md).

---

## 1. Logs e Correlação

- **Campos do Log:** Que campos todo log do Checkout precisa ter para você encontrar, no meio de milhares de linhas, as da requisição que travou? Liste os campos e diga para que serve cada um.
  > Decisão: uma linha JSON igual nos três serviços. `ts` ordena; `level` separa INFO de ERROR; `service` diz quem escreveu; `trace_id` (32 hex) é a requisição; `span_id` (16 hex) é este salto; `order_id` é o pedido; `estado` é o passo; `duracao_ms` é o tempo; `cliente_ref` é o pseudônimo; `msg` é a frase.
  > Por quê: texto livre não liga o Checkout ao Pagamento, e a imagem sem `sh` e sem SSH impede entrar na máquina.
  > Exemplo: `order_id=48213` devolve `trace_id=0af7651916cd43dd8448eb211c80319c`, `duracao_ms=2000` e `estado=PAGAMENTO_EM_VERIFICACAO`.
  > Em produção: 100% em `ESTORNANDO`, `ESTORNO_EM_REVISAO`, `ESTORNO_FALHOU`, `CANCELADO_SEM_COBRANCA` e `CANCELADO_COM_ESTORNO`, além de erro, estado de verificação (`ESTOQUE_EM_VERIFICACAO`, `PAGAMENTO_EM_VERIFICACAO`, `CONFIRMACAO_EM_VERIFICACAO`) e HTTP 5xx; 5% só no caminho feliz `PENDENTE`, `AGUARDANDO_PAGAMENTO`, `PAGO`, `CONCLUIDO`. Quatro linhas por pedido a 5.000/s seriam 20.000 linhas/s; se todo pedido estivesse no caminho feliz, 5% disso seriam cerca de 1.000 linhas/s. Esse 1.000 é só essa fatia: os estados de 100%, entre eles `CANCELADO_SEM_COBRANCA`, somam por cima, e na Black Friday essa fração pode não ser pequena.
  > Campos e estados: [anexo, logs](anexo-detalhes.md#logs).
- **O Caminho do Contexto:** Como o mesmo identificador de rastreamento passa do Checkout para o Pagamento e o Estoque numa chamada HTTP?
  > Decisão: o `traceparent` (`00-<trace 32 hex>-<span 16 hex>-01`) sai do Checkout para cada filho. Estoque e Pagamento são irmãos; o Estoque não chama o Pagamento. O comando da saga é Kafka (Aula 4), chave `orderId`, com o `traceparent` no header. O exemplo HTTP é a mesma árvore na borda. Id só de zeros é rejeitado.
  > Por quê: se o Estoque fosse pai do Pagamento, o desenho mentiria o cenário. O MDC não atravessa a thread do consumidor; o evento leva o cabeçalho.
  > Exemplo: Checkout `b7ad6b7169203331`; Estoque `00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01`; Pagamento `00-0af7651916cd43dd8448eb211c80319c-a1b2c3d4e5f67890-01`.
  > Pagamento confirmado com dinheiro em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO`. Sem captura, o estado não muda. Webhook sem o nosso trace reusa o `trace_id` do pedido; HMAC errado responde 401. Detalhe: [anexo, propagação](anexo-detalhes.md#propagacao).
- **LGPD nos Logs:** Quais dados de cliente ficam fora de logs e traces? Para os identificadores que precisam aparecer, qual tratamento você aplica e quem pode ler esses registros?
  > Decisão: ficam fora de log, trace e label: CPF, nome, e-mail, telefone, endereço, cartão, CVV e IP completo. Corpo com `cpf`, `email` ou `cartao` recebe HTTP 400.
  > `order_id` entra na linha, com acesso restrito: ainda é dado pessoal. `cliente_ref` é HMAC-SHA256 do CPF, 12 hex, chave no cofre. Com a chave a associação volta, então segue dado pessoal (Lei 13.709/2018, art. 12). O art. 13, §4º só define a técnica de pseudonimização, e é norma de pesquisa em saúde.
  > Por quê: o agregado "2% da versão nova passou de 1 s" perde o caminho até a pessoa. O `cliente_ref` não. Prazos desta entrega (art. 6º, III): trace 7 dias, log com `order_id` 15 dias, métrica agregada 30 dias. A lei não fixa esses dias.
  > Exemplo: o CPF `39053344705` não aparece na linha. Engenharia vê o painel sem `order_id`. Plantão lê a linha com login registrado. Tabela: [anexo, LGPD](anexo-detalhes.md#lgpd).

---

## 2. Métricas

- **Visão do Cliente (RED):** Quais métricas do Checkout entram no painel? Explique como você mede a duração para que o travamento de uma parte dos clientes não desapareça na média.
  > Decisão: RED por operação (`finalizar`, `reservar`, `cobrar`, `estornar`, `confirmar`). Erro da série é `resultado` em `verificacao` ou `estorno_falhou`. `ESTORNO_EM_REVISAO` conta como `verificacao` (dinheiro capturado sem devolução confirmada, até 24 h). O pedido `PENDENTE` (reserva ainda sem resposta) é caminho feliz e conta como `ok`, fora da taxa. Não é `verificacao`. Timeout, desistência do aplicativo e HTTP 5xx são contados no contador HTTP `http_server_requests_seconds`, e a taxa de erro do painel soma as duas fontes. `sem_cobranca` fica fora. Duração é histograma, com p50, p99 e a fração acima de 1 s, por versão.
  > Por quê: a média só soma o que já terminou. A tela carregando pode conviver com 120 ms, CPU abaixo de 40% e zero HTTP 500.
  > Exemplo: 9.800 × 0,07 s + 200 × 2,57 s dá média de 120 ms e p99 de 2,57 s. O alerta de média (300 ms) fica em silêncio. O de p99 (800 ms) dispara. O 7 s é o p99 da réplica do canary. O 6,0 s é a estimativa do balde desse mesmo conjunto. O 238 ms é a média geral das dez réplicas juntas. As contas estão no anexo.
  > Conversão só da coorte que entrou há mais de 15 min (já deu tempo de sair do Pix em `AGUARDANDO_PAGAMENTO`), com os estados terminais no denominador. O limiar é a taxa medida na hora anterior, como o teto de Pix. A aula não publica a conversão normal. PromQL: [anexo, RED](anexo-detalhes.md#red).
- **Visão dos Recursos (USE):** Que recursos você monitora? Que sinal acusaria saturação antes do primeiro HTTP 500?
  > Decisão: USE no pool do Postgres, no pool HTTP, no consumidor da saga (lag) e no Pix em `AGUARDANDO_PAGAMENTO`. O sinal antes do HTTP 500 é a fila, não a CPU.
  > Por quê: thread parada em socket gasta pouca CPU. A transação fecha antes da chamada ao PSP.
  > Exemplo: pool 10/10, fila 200, CPU 15%, HTTP 500 = 0. Com a fila em zero o alerta fica em silêncio. Se o `finalizar` responde 202 em 120 ms e o consumidor para, sobem o lag e `checkout_pedidos_em_aberto`.
  > Teto global = 1,5 vez a soma medida na hora anterior. O exemplo 20% × 5.000 × 30 s × R$ 180 só mostra a forma e não vira limiar. R$ 2.000 e R$ 8.000.000 no `application.yml` são fixture; o teste usa R$ 100 e dois pedidos de R$ 80. Tabela: [anexo, USE](anexo-detalhes.md#use).
- **Labels:** Quais labels você permite nessas métricas e quais proíbe? Diga o efeito de cada escolha no servidor de métricas.
  > Decisão: `servico` (3), `operacao` (5), `resultado` (6: `ok`, `sem_cobranca`, `estornado`, `estorno_falhou`, `pix_pendente`, `verificacao`) e `versao` (2). São 3 × 5 × 6 × 2 = 180, e 180 × 11 = 1.980 séries. O 11 são 9 baldes (0,05, 0,1, 0,25, 0,5, 1, 2, 4, 8 e +Inf) mais `_sum` e `_count`. `PAGO` e `PENDENTE` contam como `ok`. `ESTORNANDO` conta como `estornado`, fora da taxa dentro do prazo. `ESTORNO_EM_REVISAO` conta como `verificacao` e entra na taxa, junto com `estorno_falhou`.
  > Proibidos: `order_id`, `trace_id`, `user_id`, `cpf`, `email` e `sku_id`.
  > Por quê: um valor novo por pedido não tem teto. `order_id` fica no log, não na série. Sem `versao`, a média dos p99 (790 ms) esconderia o canary.
  > Exemplo: 1.000 observações em `v1` e `v2` geram 2 séries. `order_id` em um minuto seria 300.000 × 11 (os mesmos 9 baldes mais `_sum` e `_count`) = 3,3 milhões de séries. Efeito de cada proibido: [anexo, labels](anexo-detalhes.md#labels).

---

## 3. Traces e Amostragem

- **A Escolha:** ( ) Head Sampling  (X) Tail Sampling  ( ) Combinação das duas
- **Justificativa:** Guardar 100% dos traces a 5.000 TPS custa caro. Qual é a sua regra de retenção, e como ela evita que justamente as requisições que travaram sejam descartadas?
  > Decisão: tail puro no collector. O SDK usa `AlwaysOn`. Head na entrada descarta o lento antes de existir duração.
  > Regra: 100% com erro, 100% acima de 800 ms, 100% em verificação, `ESTORNO_EM_REVISAO` ou `ESTORNO_FALHOU`, 1% do resto pelo trace-id. Em produção `finalizar` devolve 202 e o comando segue por Kafka, via outbox. O `decision_wait` de 15 s é a janela em que os spans desse `trace_id` ainda chegam: o 202 e o do consumidor. Pix (15 min), `prazo_passo` de 30 s do `PAGO` e as retentativas do estorno são outros traces, ligados por `order_id`. Orçamentos à parte: Aula 2, 2 s de resposta do parceiro; Aula 4, 3 s na chamada de estorno. Os 8 s desta aula são a estimativa de Little e o sono do teste de estoque. O 5 s do estorno é a segunda espera, não uma chamada ao Pagamento. No laboratório, `CheckoutService.finalizar` chama reservar, cobrar e confirmar no mesmo processo só para simular a borda.
  > Por quê: a regra dos 800 ms precisa ver a espera. O histograma não entra nessa amostra: toda chamada soma no balde. `Amostrador.tail` não lê o estado: o chamador passa `erro=true` para verificação, `ESTORNO_FALHOU` e `ESTORNO_EM_REVISAO`.
  > Exemplo de custo, premissa de 6 spans e 1 KB por span (5.000 × 6 KB): 100% seriam 30 MB/s (cerca de 2,6 TB/dia). Com a regra, cerca de 150 traces/s e 80 GB/dia. Na janela de 15 s cabem 75.000 traces, cerca de 450 MB; o collector pede 2 GB. Prazo: 7 dias. Conta: [anexo, traces](anexo-detalhes.md#traces).

---

## 4. Estratégia de Testes

- **Qual a nossa estratégia para testar isso e garantir que funciona?** Antes da próxima Black Friday, como você prova que os painéis e os alertas mostrariam este incidente?
  > Decisão: a mesma régua nos quatro níveis. Com o incidente injetado, dispara o p99 ou o trabalho em andamento, e o alerta de média acima de 300 ms fica em silêncio.
  > Por quê: no incidente a média (120 ms), a CPU (abaixo de 40%) e o HTTP 500 ficaram verdes. O cliente viu a tela até o timeout.
  > Nível 1 trava 120 ms, p99 2,57 s, balde 3,0 s, 790 ms contra 7 s, 1.388 em andamento e o tail. Nível 2: sem saldo termina `CANCELADO_SEM_COBRANCA` e um `PagamentoAprovado` atrasado não muda esse estado. Nível 3: o pedido 48213 chega a `CONCLUIDO`, CPF é recusado e webhook sem assinatura volta 401.
  > Exemplo do nível 4: o k6 só mede a carga. A falha de 2% das chamadas de estoque dormindo 8 s é injetada pelos testes Java dos níveis 1 a 3. Em até 30 s o quantil ou o em andamento dispara, a média fica em silêncio, e o trace lento tem o mesmo `trace_id` nos três, com Estoque e Pagamento filhos do Checkout. `mvn verify` roda 27 testes (níveis 1 a 3). Enquanto a causa é desconhecida: abortar o canary e, acima do limiar, `503` com a mesma chave. Pedido em verificação não é cancelado. Tabelas: [anexo, testes](anexo-detalhes.md#testes).

## Referências usadas

OpenTelemetry, "Sampling" (head decide no início; tail decide com os spans em mãos; `decision_wait` é a espera antes da decisão); W3C Trace Context (`traceparent`, ids só de zeros inválidos); Lei 13.709/2018, art. 6º, III, art. 12 e art. 13, §4º; Grafana, "The RED Method"; Brendan Gregg, "The USE Method"; Prometheus, "Metric and label naming" e "Histograms and summaries"; Google SRE Book, "Postmortem Culture"; Grafana k6, "Spike testing".
