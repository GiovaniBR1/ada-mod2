# Apêndice: máquina de estados do pedido

Referência única dos nomes do pedido nas Aulas 4, 5 e 6. O mesmo pedido, o mesmo nome para a mesma situação de dinheiro e de estoque. Se um nome de pedido divergir daqui, vale este arquivo. O `CHECK` da linha de pagamento está separado por aula, na regra abaixo.

**Resposta curta.** O pedido reserva o estoque antes de cobrar. Cada estado diz, ao mesmo tempo, se o dinheiro se moveu e o que aconteceu com a unidade. Não existe estado "cancelado com Pix retido".

**Por quê.** Um nome só para situações diferentes esconde o dinheiro. Foi o que `CANCELADO` fazia: servia para "não havia unidade", para "o Pix venceu sem pagamento" e para "o Pix foi devolvido". Quem lia o pedido não sabia se precisava devolver alguma coisa.

**Exemplo.** A última TV é reservada, o cliente não paga no prazo, a reserva volta para a prateleira: `CANCELADO_SEM_COBRANCA`. Outro cliente paga, a reserva já expirou e a unidade foi vendida: o Pix volta e o pedido termina `CANCELADO_COM_ESTORNO`. Os dois são "cancelados" para a tela. Para o financeiro, são o oposto.

## O que aconteceu com os nomes antigos

| Nome que apareceu antes | O que ele era | Nome neste apêndice |
|---|---|---|
| `PENDENTE` da Aula 2 | Nome da linha de pagamento, não do pedido. | Ver a [regra única](#linha-de-pagamento). Nasce quando a chamada já saiu e a resposta é ambígua ou esgotada. |
| Espera do Pix na Aula 4 | QR criado, dinheiro ainda não capturado. | Ver a [regra única](#linha-de-pagamento). A linha nasce `AGUARDANDO_CAPTURA`. |
| `PENDENTE` da Aula 4 e da Aula 6 | Pedido chegou. A reserva ainda não respondeu. Nada foi cobrado. | Continua `PENDENTE`. Na métrica da Aula 6 o rótulo é `ok` (caminho feliz, log de 5%). Não é `verificacao`. Se o prazo estoura e a consulta não fecha, vai para `ESTOQUE_EM_VERIFICACAO`. |
| `CANCELADO` da Aula 4 | Um rótulo para três situações. | Dividido: `CANCELADO_SEM_COBRANCA`, `CANCELADO_COM_ESTORNO`, ou nem chega a cancelar (`ESTORNANDO` / `ESTORNO_EM_REVISAO` / `ESTORNO_FALHOU`). |
| `AGUARDANDO_PIX`, `CONFIRMADO`, `RESERVANDO_ESTOQUE` (textos das Aulas 5 e 6) | Sinônimos da mesma situação, com outro nome. | `AGUARDANDO_PAGAMENTO`, `CONCLUIDO`, `PENDENTE`. |
| Cinco tentativas de estorno (Aula 4) | O laço automático. Depois da quinta sem confirmação, revisão e chamado. | **Continua valendo.** É o que abre `ESTORNO_EM_REVISAO`. Não foi substituído. |
| Prazo de 24 h (Aula 6) | Não havia prazo na revisão: o chamado humano podia ficar sem fim. | **Acrescenta o fim da revisão.** 24 h em `ESTORNO_EM_REVISAO` sem confirmação do PSP abrem `ESTORNO_FALHOU`. O chamado segue aberto. O job consulta o PSP uma vez por dia e, se a devolução existir, fecha em `CANCELADO_COM_ESTORNO` sem pedir o estorno de novo. |

`Aula 4/desafio/SagaCompra.java` imprime `CANCELADO_SEM_COBRANCA` quando nada foi cobrado e `CANCELADO_COM_ESTORNO` quando o Pix foi devolvido. A Aula 6 grava `PENDENTE`, `AGUARDANDO_PAGAMENTO` e `CONCLUIDO`. O laboratório da Aula 5, no backfill do H2, ainda copia o token antigo `CONFIRMADO` para `estado_saga` (o teste espera esse valor). A escrita viva da v1.5 grava `CONCLUIDO` em `estado_saga` e `CONFIRMADO` só em `status_pagamento`. Onde o nome é o mesmo (ex.: `PAGO`), as duas colunas recebem o mesmo valor. No mapa de produção, `CONFIRMADO` vira `CONCLUIDO`.

<a id="linha-de-pagamento"></a>
## Regra única da linha de pagamento

O dinheiro segue a mesma história nas Aulas 2, 3 e 4. O `CHECK` não. "Vale este arquivo" não troca o `CHECK` do cartão pelo do QR.

- Cartão síncrono (Aulas 2 e 3): `CHECK (status IN ('PENDENTE','APROVADO','RECUSADO','EXPIRADO'))`. Ainda não existe `AGUARDANDO_CAPTURA`.
- Aula 4, com QR: `CHECK (status IN ('PENDENTE','AGUARDANDO_CAPTURA','APROVADO','RECUSADO','EXPIRADO'))`.
- A linha nasce `PENDENTE` quando a chamada já saiu e a resposta é ambígua ou esgotada. Entram timeout, 500, ou 503/429 depois das 3 tentativas. Chamada que não saiu não cria linha. Resultado claro grava `APROVADO` ou `RECUSADO`. O pedido, enquanto a linha está `PENDENTE`, fica em `PAGAMENTO_EM_VERIFICACAO`.
- Pix com QR (Aula 4): a linha nasce `AGUARDANDO_CAPTURA`, com `txid`, quando o QR é criado. O pedido fica `AGUARDANDO_PAGAMENTO` por até 15 min. O webhook de captura leva a `APROVADO`. Quinze minutos sem captura: a linha vai para `EXPIRADO` e o pedido para `CANCELADO_SEM_COBRANCA`.
- Estado final da linha (`APROVADO`, `RECUSADO`, `EXPIRADO`) não muda. A única exceção: o webhook de captura que chega com a linha já em `EXPIRADO` muda `pagamento.status` de `EXPIRADO` para `APROVADO`. O pedido que está em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO`.

## Estados

Prazo de cada passo não final: `prazo_passo`. O exemplo usado no desenho é 30 s para uma resposta de serviço e 15 min para o cliente pagar o Pix. `PAGO` entra nesse prazo: sem ele, um pedido cobrado cuja reserva não responde fica cobrado para sempre.

| Estado | Dinheiro | Estoque | Sai sozinho? |
|---|---|---|---|
| `PENDENTE` | nada cobrado | reserva ainda não respondida | não |
| `ESTOQUE_EM_VERIFICACAO` | nada cobrado | timeout e a consulta da reserva não fechou | não |
| `AGUARDANDO_PAGAMENTO` | Pix gerado, não capturado | reserva `ATIVA`, com prazo | não |
| `PAGAMENTO_EM_VERIFICACAO` | pode ter sido capturado; a consulta não fechou | reserva `ATIVA` | não |
| `PAGO` | capturado | confirmação da reserva ainda no prazo | não |
| `CONFIRMACAO_EM_VERIFICACAO` | capturado | a confirmação não respondeu e a consulta não fechou | não |
| `CONCLUIDO` | capturado | reserva `CONFIRMADA` | sim, fim |
| `CANCELADO_SEM_COBRANCA` | nada capturado | sem reserva, ou reserva `LIBERADA` | fim, salvo Pix atrasado |
| `ESTORNANDO` | capturado; estorno pedido, dentro das 5 tentativas | reserva rejeitada ou `LIBERADA` | não |
| `ESTORNO_EM_REVISAO` | capturado; PSP ainda não confirmou a devolução | `LIBERADA` | não |
| `ESTORNO_FALHOU` | capturado; 24 h sem confirmação; chamado aberto | `LIBERADA` | não; o job diário ainda fecha |
| `CANCELADO_COM_ESTORNO` | devolução confirmada pelo PSP | `LIBERADA` | sim, fim |

```mermaid
stateDiagram-v2
  [*] --> PENDENTE: checkout recebido
  PENDENTE --> AGUARDANDO_PAGAMENTO: estoque reservado (prazo 15 min)
  PENDENTE --> CANCELADO_SEM_COBRANCA: sem estoque (nada cobrado)
  PENDENTE --> ESTOQUE_EM_VERIFICACAO: reserva sem resposta no prazo
  ESTOQUE_EM_VERIFICACAO --> AGUARDANDO_PAGAMENTO: consulta achou a reserva
  ESTOQUE_EM_VERIFICACAO --> CANCELADO_SEM_COBRANCA: consulta diz que não reservou
  AGUARDANDO_PAGAMENTO --> PAGO: Pix confirmado
  AGUARDANDO_PAGAMENTO --> CANCELADO_SEM_COBRANCA: Pix não pago no prazo (reserva liberada)
  AGUARDANDO_PAGAMENTO --> PAGAMENTO_EM_VERIFICACAO: PSP sem resposta e consulta aberta
  PAGAMENTO_EM_VERIFICACAO --> PAGO: consulta diz pago
  PAGAMENTO_EM_VERIFICACAO --> CANCELADO_SEM_COBRANCA: consulta diz não pago
  PAGO --> CONCLUIDO: reserva confirmada
  PAGO --> ESTORNANDO: reserva rejeitada
  PAGO --> CONFIRMACAO_EM_VERIFICACAO: confirmação sem resposta no prazo
  CONFIRMACAO_EM_VERIFICACAO --> CONCLUIDO: consulta achou reserva confirmada
  CONFIRMACAO_EM_VERIFICACAO --> ESTORNANDO: consulta diz reserva rejeitada
  CANCELADO_SEM_COBRANCA --> [*]
  CANCELADO_SEM_COBRANCA --> ESTORNANDO: Pix chegou depois do cancelamento
  ESTORNANDO --> CANCELADO_COM_ESTORNO: Pix devolvido
  ESTORNANDO --> ESTORNO_EM_REVISAO: 5 tentativas sem confirmação
  ESTORNO_EM_REVISAO --> CANCELADO_COM_ESTORNO: PSP confirmou
  ESTORNO_EM_REVISAO --> ESTORNO_FALHOU: 24 h sem confirmação
  ESTORNO_FALHOU --> CANCELADO_COM_ESTORNO: consulta diária confirma
  CONCLUIDO --> [*]
  CANCELADO_COM_ESTORNO --> [*]
```

Toda transição é condicional: `UPDATE pedidos SET status = :novo, version = version + 1 WHERE id = :id AND status = :esperado`. Zero linhas significam mensagem velha ou repetida. A exceção do Pix atrasado está na [regra única](#linha-de-pagamento).

## Política de prazo, tentativa e estorno

1. O relógio de `prazo_passo` varre todo estado que não é fim quando o prazo vence. Isso inclui `PAGO` (ex.: 30 s). Não é o job de 5 min.
2. O job **não cria mensagem nova**. Reenvia o mesmo comando: o mesmo `message_id` (chave estável `pedido_id` + tipo do passo) e a mesma chave de negócio (no estorno, o id do pagamento). Um `message_id` novo passaria na chave de `mensagens_processadas` e executaria o efeito outra vez.
3. Timeout não é falha. Antes de decidir, o job consulta o participante (Estoque pela reserva, PSP pelo pagamento ou pelo estorno). Status desconhecido cai no estado `*_EM_VERIFICACAO` correspondente. Não confirma, não cancela e não estorna no escuro.
4. Estorno, sempre a mesma chave. Falha 1 espera 1 s. Falha 2 espera 5 s. Falha 3 espera 30 s. Falha 4 espera 2 min. A falha 5 abre `ESTORNO_EM_REVISAO`, com mensagem na DLQ e chamado. Não há quinta espera. Poll é uma consulta repetida, curta o bastante para a espera de 1 s. O cliente vê "devolução em análise".
5. O job de 5 min só olha a revisão de 24 h e a consulta diária. 24 h em `ESTORNO_EM_REVISAO` sem confirmação: `ESTORNO_FALHOU`. O chamado continua. A consulta diária ainda pode concluir a devolução. As cinco tentativas abrem a revisão; as 24 h impedem que a revisão não tenha fim. Uma não substitui a outra.
