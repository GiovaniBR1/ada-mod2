# Anexo da Aula 4 — fluxos, modelo e testes

O texto principal responde cada pergunta em poucas linhas. O diagrama e a regra da linha de pagamento estão no [apêndice](apendice-maquina-de-estados.md).

<a id="fluxos"></a>
## Fluxos

Caminho feliz.

1. Checkout grava o pedido `PENDENTE` e o comando `ReservarEstoque` na outbox (uma transação).
2. Estoque reserva (`UPDATE` atômico da Aula 3, reserva `ATIVA` com prazo) e grava `EstoqueReservado` na sua outbox.
3. Orquestrador muda para `AGUARDANDO_PAGAMENTO` e emite `CobrarPagamento`. O Pagamento gera o QR Code do Pix e grava a linha `AGUARDANDO_CAPTURA`, com o `txid` do QR.
4. O PSP avisa que o Pix caiu; o Pagamento grava `APROVADO` e `PagamentoAprovado`.
5. Orquestrador muda para `PAGO` e emite `ConfirmarReserva`. Estoque muda a reserva para `CONFIRMADA` e responde `ReservaConfirmada`.
6. Pedido `CONCLUIDO`.

Caminho de falha e compensação (o incidente).

1. No passo 5, o Estoque encontra a reserva já `LIBERADA` (expirou e a unidade foi vendida) e responde `ReservaRejeitada`.
2. Orquestrador muda para `ESTORNANDO` e emite `EstornarPagamento`, na mesma transação.
3. O Pagamento pede a devolução do Pix ao PSP. A linha de pagamento permanece `APROVADO` e a tabela `estorno` vai a `CONCLUIDO`. Responde `PagamentoEstornado`.
4. Orquestrador muda para `CANCELADO_COM_ESTORNO`. O cliente recebe a notificação "o produto esgotou, devolvemos seu Pix".

Pix que chega tarde. Se a confirmação do PSP chega com o pedido já em `CANCELADO_SEM_COBRANCA` (venceu sem pagamento), o evento não é descartado: o dinheiro entrou. O orquestrador muda para `ESTORNANDO` e emite `EstornarPagamento` na hora, com a mesma chave. O fim, quando o PSP confirma, é `CANCELADO_COM_ESTORNO`.

Quem varre o que parou. Cada passo feliz é disparado por um evento. O relógio de `prazo_passo` varre todo estado que não é fim com o prazo vencido, inclusive `PAGO` (ex.: 30 s sem resposta do Estoque). Não é o job de 5 min. O estorno espera em `proxima_tentativa_em` (1 s, 5 s, 30 s, 2 min, 10 min), com poll curto para a espera de 1 s. O job de 5 min só vê a revisão de 24 h e a consulta diária. O varredor de `prazo_passo` não grava mensagem nova: reenvia o mesmo comando, com o mesmo `message_id` (chave estável `pedido_id` + tipo do passo, não um uuid novo) e a mesma chave de negócio. Um id novo passaria na PK de `mensagens_processadas` e executaria o efeito outra vez. Em `PAGO`, ele consulta a reserva: confirmada vira `CONCLUIDO`; rejeitada vai para `ESTORNANDO`; desconhecida vai para `CONFIRMACAO_EM_VERIFICACAO` e não estorna enquanto o estoque for desconhecido.

<a id="modelo"></a>
## Modelo

Cada tabela mora no banco do seu serviço. IDs `uuid` v7 e status `text` com `CHECK`, como na Aula 3.

| Serviço | Tabela | Campos principais | Constraints e regras |
|---|---|---|---|
| Checkout | `pedidos` | `id`, `customer_id`, `status`, `total numeric(12,2)`, `prazo_passo timestamptz`, `estorno_pendente boolean`, `version bigint` | `CHECK` com os doze estados do [apêndice](apendice-maquina-de-estados.md) (`PENDENTE`, `ESTOQUE_EM_VERIFICACAO`, `AGUARDANDO_PAGAMENTO`, `PAGAMENTO_EM_VERIFICACAO`, `PAGO`, `CONFIRMACAO_EM_VERIFICACAO`, `CONCLUIDO`, `CANCELADO_SEM_COBRANCA`, `ESTORNANDO`, `ESTORNO_EM_REVISAO`, `ESTORNO_FALHOU`, `CANCELADO_COM_ESTORNO`); `estorno_pendente` é verdadeiro somente em `ESTORNANDO`, `ESTORNO_EM_REVISAO` e `ESTORNO_FALHOU`, e falso em todos os outros estados; índice parcial em `prazo_passo` só para estados não finais (o relógio de `prazo_passo`, não o job de 5 min). |
| Todos | `outbox` | `id`, `pedido_id`, `tipo`, `payload jsonb`, `criado_em` | Particionada por dia; gravada na mesma transação do dado. |
| Todos | `mensagens_processadas` | `message_id`, `processada_em` | PK `message_id`: a trava contra mensagem repetida. |
| Estoque | `reserva` | (Aula 3) `order_id`, `sku`, `status`, `expira_em` | `UNIQUE (order_id, sku)`; muda de `ATIVA` uma vez só. |
| Pagamento | `pagamento` | `id`, `order_id`, `valor numeric(12,2)`, `status text`, `version bigint`, `txid text`, `e2e_id text` | `UNIQUE (order_id)`; `UNIQUE (txid)`: id do QR, o webhook acha a linha por ele; `UNIQUE (e2e_id)`: id do Pix no Banco Central, contra confirmação duplicada. `CHECK (status IN ('PENDENTE','AGUARDANDO_CAPTURA','APROVADO','RECUSADO','EXPIRADO'))`, o mesmo das Aulas 2 e 3. O que cada valor significa está na [regra única](apendice-maquina-de-estados.md#linha-de-pagamento). |
| Pagamento | `estorno` | `id`, `pagamento_id`, `valor`, `status`, `tentativas int`, `proxima_tentativa_em` | `UNIQUE (pagamento_id)`: um estorno por pagamento; `CHECK (status IN ('SOLICITADO','CONCLUIDO','FALHOU'))`; `CHECK (tentativas <= 5)`. |

<a id="testes"></a>
## Testes

Teste de integração principal: "Pix aprovado, estoque rejeita". JUnit 5 com Testcontainers subindo Kafka, Debezium e um PostgreSQL por serviço, e o PSP no WireMock.

1. Dado um produto com 1 unidade e uma reserva já expirada e vendida a outra pessoa.
2. Quando o WireMock confirma o Pix e o Estoque responde `ReservaRejeitada`.
3. Então, esperando com Awaitility (até 10 s, sem `sleep` fixo): o pedido termina `CANCELADO_COM_ESTORNO`; o WireMock recebeu exatamente 1 pedido de devolução com a chave = id do pagamento; a projeção mostra "Cancelado, Pix devolvido"; nenhuma mensagem na DLQ.

| Falha injetada | Como | O que deve acontecer |
|---|---|---|
| PSP oscila no estorno | WireMock responde `503` duas vezes, depois `200` | 1 devolução só; pedido `CANCELADO_COM_ESTORNO` |
| PSP fora do ar no estorno | WireMock sempre `503` | `ESTORNO_EM_REVISAO`, mensagem na DLQ, nunca `CANCELADO_COM_ESTORNO`; após 24 h, `ESTORNO_FALHOU`, ainda sem cancelar |
| Timeout do estorno | WireMock com atraso de 5 s | consulta antes de repetir; 1 devolução só |
| Relay cai depois de publicar | Reiniciar o container do Debezium no meio | mensagens repetidas são descartadas; o fluxo termina igual |
| Mensagem duplicada | Publicar o mesmo `message_id` duas vezes | o efeito acontece uma vez |

Esses testes rodam em todo PR que mexe na Saga (levam minutos, não segundos, por isso ficam separados dos testes de unidade). A máquina de estados tem também testes de unidade para cada transição, incluindo as proibidas (de `CONCLUIDO` e de `CANCELADO_COM_ESTORNO` não se sai; de `CANCELADO_SEM_COBRANCA` só se sai se o Pix chegar depois).

A pasta `desafio/` tem uma simulação em Java puro com os seis cenários (caminho feliz, o incidente, estorno instável, estorno que sempre falha, relay que cai, sem estoque) e a saída real da execução. Ela não substitui o teste com Kafka e Postgres de verdade. Sem estoque o estado impresso é `CANCELADO_SEM_COBRANCA`; com Pix devolvido, `CANCELADO_COM_ESTORNO`.
