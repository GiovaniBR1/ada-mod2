# Perguntas de Risco: Aula 04 - Saga da Compra

> **Material extra:** a Aula 04 não pede perguntas de risco no template; este arquivo foi mantido como estudo e para reforçar a robustez do desenho.

**Cenário.** É o mesmo checkout das Aulas 2 e 3, agora dividido em três serviços, cada um com o seu banco PostgreSQL:

- **Checkout:** dono do pedido (tabela `pedidos`) e da **Saga de Compra**, o coordenador que decide o próximo passo.
- **Estoque:** dono das reservas (tabelas `estoque` e `reserva`).
- **Pagamento:** dono das cobranças e devoluções (tabelas `pagamento` e `estorno`). Ele fala com o **PSP**, a empresa externa que movimenta o Pix.

Os serviços conversam por mensagens no Kafka. Nenhum serviço publica direto: cada um grava a mensagem na sua tabela `outbox`, na mesma transação do dado, e o Debezium a publica. Por isso uma mensagem pode chegar **mais de uma vez**, e todo serviço guarda os ids já processados na tabela `mensagens_processadas`.

A ordem da compra é: **reservar o estoque (prazo de 15 min) → cobrar o Pix → confirmar a reserva**. A regra que nenhum risco pode quebrar: **o cliente nunca fica cobrado sem produto e sem devolução**.

As três perguntas abaixo seguem o mesmo roteiro: **a história** (quem, o quê, em que ordem), **o que dá errado por dentro** (tabelas, estados, mensagens), **como protegemos, passo a passo** e **como testamos**. Os horários e valores são exemplos para tornar a história concreta.

---

## 1. E se a confirmação do Pix chegar **depois** de o pedido ter sido cancelado por falta de pagamento?

### A história

Black Friday, 23h40. A Ana compra uma Smart TV de **R$ 3.000**.

| Hora | O que acontece |
|---|---|
| 23:40:00 | O Checkout cria o pedido `P-100` como `PENDENTE`. O Estoque reserva a última TV até **23:55:00**. O pedido passa para `AGUARDANDO_PAGAMENTO` e o Pagamento gera o QR Code do Pix, que também vence às 23:55:00. |
| 23:54:58 | A Ana paga pelo app do banco. O banco dela debita R$ 3.000 na hora. |
| 23:55:00 | O **job de prazos** do Checkout (um processo que roda a cada poucos segundos procurando pedidos parados) acha `P-100` com o prazo vencido. Muda o pedido para `CANCELADO` e manda o Estoque liberar a reserva. |
| 23:55:01 | O Estoque devolve a TV à prateleira. Às 23:55:03, o Bruno compra essa TV. |
| 23:55:20 | O PSP, atrasado pela carga da Black Friday, finalmente avisa o Pagamento: "Pix de R$ 3.000 recebido para o pagamento `PG-100`". |

### O que dá errado por dentro

O Pagamento grava `pagamento.status = 'APROVADO'` e publica `PagamentoAprovado` para o pedido `P-100`. O orquestrador recebe a mensagem, procura a transição "de `AGUARDANDO_PAGAMENTO` para `PAGO`" e faz:

```sql
UPDATE pedidos SET status = 'PAGO', version = version + 1
 WHERE id = 'P-100' AND status = 'AGUARDANDO_PAGAMENTO';
-- 0 linhas: o pedido já está CANCELADO
```

Zero linhas mudaram. Num desenho ingênuo, "0 linhas" significa "mensagem velha, ignore" (é o que usamos contra mensagens repetidas). O resultado seria a Ana com **R$ 3.000 a menos, pedido cancelado e nenhuma devolução marcada**. Ninguém mais olha para esse pedido, porque `CANCELADO` é um estado final. É o incidente da aula voltando por outra porta.

### Como protegemos, passo a passo

1. **"Pagamento aprovado em pedido cancelado" é um caso com tratamento próprio, nunca um descarte.** Quando o `UPDATE` volta 0 linhas, o orquestrador relê o pedido. Se o status for `CANCELADO` e a mensagem for `PagamentoAprovado`, isso não é mensagem velha: o dinheiro entrou.
2. **O orquestrador pede a devolução na hora.** Na mesma transação, ele grava em `pedidos` uma marca `estorno_pendente = true` e coloca na `outbox` o comando `EstornarPagamento { pedido_id: P-100, pagamento_id: PG-100, valor: 3000.00 }`.
3. **O Pagamento devolve uma vez só.** Ele faz `INSERT INTO estorno (pagamento_id, valor, status) VALUES ('PG-100', 3000.00, 'SOLICITADO')`. Como existe `UNIQUE (pagamento_id)`, um segundo comando igual não cria outra linha. Ele chama o PSP com a **chave de idempotência = `PG-100`**.
4. **O PSP confirma.** O Pagamento grava `estorno.status = 'CONCLUIDO'` e `pagamento.status = 'ESTORNADO'`, e publica `PagamentoEstornado`.
5. **O pedido continua `CANCELADO`**, agora com `estorno_pendente = false`. A Ana recebe: "Seu pagamento chegou depois do prazo e foi devolvido". A TV continua com o Bruno, sem disputa.

**Rede de segurança:** um job de **reconciliação** roda a cada 10 minutos e procura pagamentos `APROVADO` cujo pedido está `CANCELADO` e sem estorno. Se o passo 1 falhar por um bug, o job pega o caso em minutos.

**Escolha de negócio:** decidimos **devolver**, e não "ressuscitar" o pedido pegando outra unidade. A unidade pode não existir mais e reabrir um pedido cancelado confunde o cliente. Se o negócio preferir vender (quando houver estoque), isso vira um caminho novo da Saga, decidido de propósito.

### Como testamos

Teste de integração com Testcontainers (Kafka e um Postgres por serviço) e o PSP simulado no WireMock:

1. Criar o pedido e a reserva com prazo de **2 segundos** (no teste, em vez de 15 min).
2. Configurar o WireMock para confirmar o Pix só **5 segundos** depois.
3. Esperar o job de prazos cancelar o pedido.
4. Deixar o PSP simulado avisar "Pix recebido".
5. **Conferir** (esperando com Awaitility até 10 s): pedido `CANCELADO`; uma linha em `estorno` com status `CONCLUIDO`; o WireMock recebeu **exatamente 1** pedido de devolução, com a chave `PG-100`; a reserva continua `LIBERADA`.
6. **Variante da rede de segurança:** desligar o tratamento do passo 1 e rodar a reconciliação manualmente; a devolução tem de acontecer do mesmo jeito.

---

## 2. E se o PSP devolver o Pix, mas a resposta se perder, e o Pagamento tentar devolver de novo?

### A história

É o incidente da aula, já na fase da compensação. O Carlos pagou R$ 3.000, a confirmação da reserva falhou (a TV tinha sido vendida) e o pedido `P-200` foi para `ESTORNANDO`.

| Hora | O que acontece |
|---|---|
| 21:10:00 | O Pagamento recebe `EstornarPagamento` para `PG-200` e chama o PSP: "devolva R$ 3.000". O limite de espera da chamada é de **3 segundos**. |
| 21:10:01 | O PSP **faz a devolução**: o dinheiro já está voltando para o Carlos. |
| 21:10:01 | A resposta "devolvido" se perde no caminho (a conexão cai). |
| 21:10:03 | O Pagamento estoura os 3 segundos sem resposta. Ele não sabe se o PSP devolveu ou não. |

### O que dá errado por dentro

Existem dois jeitos errados de reagir:

- **Tentar de novo sem cuidado:** o Pagamento chama o PSP outra vez. Se essa chamada não carregar nada que diga "é a mesma devolução", o PSP faz uma **segunda devolução**. O Carlos recebe **R$ 6.000** de volta por uma TV de R$ 3.000. Na Black Friday, com milhares de estornos e um PSP lento, isso vira prejuízo grande e em série.
- **Desistir:** o Pagamento marca "falhou" e para. A tabela `estorno` fica `SOLICITADO`, o pedido fica em `ESTORNANDO` para sempre e o Carlos vê "devolvendo seu Pix" por dias, mesmo com o dinheiro já de volta.

### Como protegemos, passo a passo

1. **Uma devolução tem um "RG" fixo.** A chave de idempotência enviada ao PSP é o **id do pagamento** (`PG-200`), não um número novo a cada tentativa. Toda tentativa leva a mesma chave, e o PSP responde "essa já foi feita" em vez de devolver de novo. No nosso banco, `UNIQUE (pagamento_id)` em `estorno` garante uma linha só.
2. **Timeout não é falha: é dúvida.** Ao estourar os 3 segundos, o Pagamento grava `estorno.status = 'SOLICITADO'`, `tentativas = 1`, `proxima_tentativa_em = agora + 5 s`. Nada é dado como falho.
3. **Antes de pedir de novo, pergunta.** Às 21:10:08, o Pagamento **consulta** o PSP: "qual a situação da devolução `PG-200`?". É o mesmo raciocínio do pagamento `PENDENTE` da Aula 2.
   - Se o PSP diz "devolvida": grava `CONCLUIDO`, publica `PagamentoEstornado` e o pedido vai para `CANCELADO`. Só uma devolução existiu.
   - Se o PSP diz "não encontrada": aí sim pede a devolução de novo, com a mesma chave.
4. **Espera crescente entre tentativas:** 5 s, 30 s, 2 min, 10 min. Se o PSP estiver doente, o disjuntor da Aula 2 abre e as tentativas esperam, em vez de bombardeá-lo.
5. **Depois de 5 tentativas sem resposta**, o pedido vai para `ESTORNO_EM_REVISAO`, a mensagem vai para a DLQ (a fila de mensagens com problema) e um alerta abre um chamado para a equipe financeira. O Carlos vê "devolução em análise", nunca "cancelado" sem o dinheiro.

### Como testamos

1. **Resposta perdida:** o WireMock **registra** a devolução e responde com atraso de **5 segundos** (maior que o limite de 3 s). Na consulta seguinte, responde "devolvida".
   **Conferir:** o WireMock recebeu 1 pedido de devolução e 1 consulta; o pedido terminou `CANCELADO`; `estorno.tentativas = 1`.
2. **PSP instável:** o WireMock responde `503` (fora do ar) duas vezes e `200` na terceira.
   **Conferir:** todas as chamadas levaram a mesma chave `PG-200`; uma devolução só; pedido `CANCELADO`.
3. **PSP fora do ar o tempo todo:** o WireMock sempre `503`.
   **Conferir:** depois de 5 tentativas o pedido está `ESTORNO_EM_REVISAO`, existe 1 mensagem na DLQ e o pedido **nunca** passou por `CANCELADO`.

A simulação em `desafio/SagaCompra.java` já cobre os casos 2 e 3, em memória (cenários 3 e 4).

---

## 3. E se a tela "Meus Pedidos" receber "concluído" antes de "pago" (mensagens fora de ordem ou repetidas)?

### A história

A Daniela compra um fone de R$ 400. O pedido `P-300` passa por `PAGO` (versão 3) e logo `CONCLUIDO` (versão 4), com diferença de 80 milissegundos.

A tela "Meus Pedidos" não lê o banco do Checkout. Ela lê uma **cópia de leitura** num Redis, atualizada por um consumidor que escuta as mensagens (é o CQRS da aula). Na Black Friday esse consumidor roda em várias instâncias e, às vezes, reprocessa mensagens.

| Hora | O que acontece |
|---|---|
| 15:00:00.000 | Evento `PagamentoAprovado` (versão 3) é publicado. |
| 15:00:00.080 | Evento `PedidoConcluido` (versão 4) é publicado. |
| 15:00:00.100 | A cópia de leitura recebe "concluído" e mostra **"Confirmado"**. A Daniela vê e fica tranquila. |
| 15:00:02.000 | Uma instância que tinha travado reprocessa "pago" e grava **"Pago, confirmando estoque"**. |

### O que dá errado por dentro

O Redis guarda `meus_pedidos:{cliente}` → `P-300: "Confirmado"`. Se o consumidor simplesmente **sobrescreve** com a última mensagem que chegou, a mensagem atrasada apaga a mais nova. A tela "volta no tempo": de "Confirmado" para "Pago, confirmando estoque". A Daniela acha que a compra foi desfeita, abre um chamado ou compra de novo (e aí sim é cobrada duas vezes). Multiplicado por milhões de clientes, isso enche o atendimento.

### Como protegemos, passo a passo

1. **Cada mudança do pedido tem um número crescente.** A coluna `pedidos.version` sobe 1 a cada transição, e toda mensagem leva esse número: `PagamentoAprovado { pedido: P-300, version: 3 }`, `PedidoConcluido { pedido: P-300, version: 4 }`.
2. **A cópia de leitura guarda o número junto com o status:** `P-300 → { status: "Confirmado", version: 4 }`.
3. **Só aceita o que é mais novo.** Ao chegar uma mensagem, o consumidor compara: se a versão que chegou é **maior** que a guardada, atualiza; se é **menor ou igual**, descarta. No Redis, essa comparação e a gravação acontecem num passo só (um pequeno script), para duas instâncias não se atropelarem.
4. A mensagem atrasada "pago" (versão 3) encontra a versão 4 guardada e é **descartada**. A tela continua em "Confirmado".
5. **Ajuda extra:** a chave da mensagem no Kafka é o `pedido_id`, então as mensagens de um mesmo pedido normalmente chegam em ordem. Os passos 1 a 3 cobrem o "normalmente" (reprocessamento, instância travada).

### Como testamos

1. **Teste de unidade do consumidor:** entregar `PedidoConcluido` (versão 4) e depois `PagamentoAprovado` (versão 3).
   **Conferir:** a cópia mostra "Confirmado", versão 4.
2. **Repetição:** entregar a mesma mensagem duas vezes.
   **Conferir:** nada muda na segunda.
3. **Integração com Testcontainers (Kafka + Redis):** publicar as duas mensagens fora de ordem e ler a tela pela API de consulta.
   **Conferir:** a resposta é "Confirmado" e nunca passa por "Pago" depois disso. Para pegar a "volta no tempo", a API é consultada 20 vezes durante 2 segundos.
4. **Concorrência:** duas instâncias do consumidor recebendo as duas mensagens ao mesmo tempo, repetido 100 vezes.
   **Conferir:** em todas as rodadas, o resultado final é a versão 4.
