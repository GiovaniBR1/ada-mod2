# Perguntas de Risco: Aula 04 - Saga da Compra

Material extra: o template da Aula 04 não pede estas perguntas. Elas reforçam o `system-design.md`. Os estados são os do [apêndice](apendice-maquina-de-estados.md).

Três serviços, três bancos, mensagens pelo outbox e pelo Kafka. A ordem é reservar (15 min), cobrar o Pix, confirmar a reserva. A regra: o cliente não fica cobrado sem produto e sem devolução.

Cada pergunta responde direto, depois o porquê, depois um exemplo e o teste.

---

## 1. E se a confirmação do Pix chegar depois de o pedido ter sido cancelado por falta de pagamento?

**Resposta.** Não é mensagem velha. O dinheiro entrou. O pedido sai de `CANCELADO_SEM_COBRANCA`, vai para `ESTORNANDO` e termina em `CANCELADO_COM_ESTORNO`.

**Por quê.** O `UPDATE ... WHERE status = 'AGUARDANDO_PAGAMENTO'` volta 0 linhas, porque o prazo já mudou o pedido. Tratar todo "0 linhas" como descarte deixaria o Pix retido num estado que ninguém mais olha. `CANCELADO_SEM_COBRANCA` é fim só enquanto nenhum dinheiro chegou. A única saída é esta.

**Exemplo.** Reserva até 23:55. O job libera a unidade no vencimento e outra compra a leva. Às 23:55:20 o PSP avisa que o Pix caiu. O orquestrador relê, grava `estorno_pendente = true` e publica `EstornarPagamento` com a chave igual ao id do pagamento. O booleano é verdadeiro somente em `ESTORNANDO`, `ESTORNO_EM_REVISAO` e `ESTORNO_FALHOU`, e falso em todos os outros estados. Volta a falso quando o PSP confirma e o pedido chega a `CANCELADO_COM_ESTORNO`. `UNIQUE (pagamento_id)` impede um segundo estorno. O cliente vê que o pagamento atrasado foi devolvido. A unidade fica com quem comprou depois.

**Como testamos.** Kafka, um Postgres por serviço, PSP no WireMock.

1. Reserva com prazo de 2 s. O WireMock confirma o Pix 5 s depois.
2. O job cancela por prazo (`CANCELADO_SEM_COBRANCA`) e libera a reserva.
3. O aviso de Pix recebido chega. Em até 10 s (Awaitility): pedido `CANCELADO_COM_ESTORNO`, uma linha de estorno `CONCLUIDO`, exatamente uma devolução com a chave do pagamento, reserva `LIBERADA`.
4. Variante: desligar o tratamento do "0 linhas" e rodar a reconciliação. A devolução acontece do mesmo jeito.

Devolver, e não reabrir o pedido, é a escolha: a unidade pode não existir mais. Vender de novo, se houver estoque, seria outro caminho da saga, decidido de propósito.

---

## 2. E se o PSP devolver o Pix, a resposta se perder, e o Pagamento tentar devolver de novo?

**Resposta.** A segunda tentativa é o mesmo estorno. A chave no PSP é o id do pagamento, e o `message_id` do comando não muda. Timeout leva a uma consulta, não a uma devolução nova.

**Por quê.** Uma chave nova faria o PSP devolver outra vez. Um `message_id` novo passaria na PK de `mensagens_processadas` e executaria o comando de novo, mesmo com a chave do PSP. Desistir deixaria o pedido em `ESTORNANDO` com o dinheiro já a caminho. Por isso timeout é dúvida: grava `SOLICITADO`, consulta o PSP pela mesma chave e só pede de novo se ele disser que não encontrou.

**Exemplo.** O PSP devolve em 1 s e a resposta se perde. O timeout de 3 s dispara. A consulta seguinte responde "devolvida": uma devolução, pedido `CANCELADO_COM_ESTORNO`, `tentativas = 1`. Se o PSP responder `503` a cada consulta, as cinco tentativas (1 s, 5 s, 30 s, 2 min, 10 min) em `proxima_tentativa_em`, com poll curto para a de 1 s, abrem `ESTORNO_EM_REVISAO`, DLQ e chamado. O pedido não vai para `CANCELADO_COM_ESTORNO`. Às 24 h sem confirmação, o job de 5 min (só essa revisão e a consulta diária) passa o pedido a `ESTORNO_FALHOU`; a consulta diária ainda pode fechar, sem segunda devolução. `PAGO` segue no `prazo_passo` (ex.: 30 s), fora desse job. As cinco tentativas abrem a revisão. As 24 h impedem que ela não tenha fim.

**Como testamos.**

1. WireMock registra a devolução e responde em 5 s. Na consulta, "devolvida". Uma devolução, um consulta, pedido `CANCELADO_COM_ESTORNO`.
2. `503` duas vezes e `200` na terceira. A mesma chave em todas. Uma devolução.
3. `503` sempre. Depois de cinco tentativas, `ESTORNO_EM_REVISAO`, uma mensagem na DLQ, nunca `CANCELADO_COM_ESTORNO`.

O `desafio/SagaCompra.java` cobre esses dois desfechos em memória. O estorno que passa na 3ª (cenário 3 do programa, o caso 2 desta pergunta) imprime `CANCELADO_COM_ESTORNO`. O estorno que sempre falha (cenário 4 do programa, o caso 3 desta pergunta) imprime `ESTORNO_EM_REVISAO`, não `CANCELADO_COM_ESTORNO`.

---

## 3. E se a tela "Meus Pedidos" receber "concluído" antes de "pago"?

**Resposta.** A projeção só aplica o evento se a `version` for maior que a guardada. Evento atrasado não volta o status.

**Por quê.** A tela lê uma cópia (Redis), não o banco do Checkout. Várias instâncias do consumidor reprocessam mensagens. Sobrescrever com a última que chegou faz a tela voltar de `CONCLUIDO` para `PAGO`. O cliente acha que a compra desfez e compra de novo.

**Exemplo.** `PagamentoAprovado` versão 3, `PedidoConcluido` versão 4, 80 ms depois. A cópia grava "Confirmado" (o texto da tela para `CONCLUIDO`) com versão 4. Dois segundos depois uma instância atrasada reprocessa a versão 3. A comparação, num passo só no Redis, descarta. A chave Kafka `pedido_id` já tende a entregar em ordem; a versão cobre o reprocessamento.

**Como testamos.**

1. Unidade: entregar versão 4 e depois versão 3. A cópia fica na versão 4.
2. A mesma mensagem duas vezes não muda nada.
3. Kafka e Redis, mensagens fora de ordem. A API de consulta responde o estado concluído e não volta a "pago" em 20 leituras.
4. Duas instâncias ao mesmo tempo, 100 rodadas. O fim é sempre a versão 4.
