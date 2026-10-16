# Perguntas de Risco: Aula 03 - Banco de Dados do Checkout

> **Material extra:** a Aula 03 não pede perguntas de risco no template; este arquivo foi mantido como estudo e para reforçar a robustez do desenho.

**Cenário.** É o mesmo checkout da Aula 2, agora na **Black Friday**. A borda aguenta o tráfego, e o gargalo passou para a **única instância do PostgreSQL**. Centenas de clientes disputam as últimas unidades do mesmo produto em promoção, e uma consulta de pedidos sem índice já esgotou o **pool de conexões com o banco** (o conjunto fixo de conexões abertas que a aplicação reaproveita), com p99 de 4,8 s (99% das requisições abaixo de 4,8 s).

O que o nosso desenho (`system-design.md`) já tem, e que as respostas abaixo usam:

- **Duas transações curtas** (transação = um grupo de gravações que entram todas ou nenhuma):
  - **T1**, em milissegundos: grava a `Idempotency-Key`, cria o pedido, o pagamento `PENDENTE` e a reserva `ATIVA` (com prazo `expira_em`) e, **por último**, baixa o estoque. Commit.
  - Depois, a chamada ao parceiro de pagamento, **fora de qualquer transação**.
  - **T2:** fecha o pagamento e, na mesma transação, confirma ou libera a reserva.
- **Pagamento com lock otimista (`@Version`):** a tabela `pagamento` tem uma coluna `version`. Toda gravação confere se a versão ainda é a que foi lida; se alguém mudou antes, a gravação não acontece.
- **Estoque com `UPDATE` atômico:** `UPDATE estoque SET disponivel = disponivel - :qtd WHERE sku = :sku AND disponivel >= :qtd`, e `CHECK (disponivel >= 0)` na tabela.
- **Reserva** com status `ATIVA`, `CONFIRMADA` ou `LIBERADA`, que muda **uma vez só**: toda mudança tem `WHERE status = 'ATIVA'`.
- **Reconciliação** da Aula 2: um job (tarefa automática periódica) que fecha pagamentos `PENDENTE` consultando o parceiro.

A regra: **o estoque nunca fica negativo, um pagamento só fecha uma vez e uma falha nunca vira "aprovado"**.

Cada pergunta segue o mesmo roteiro: **a história** (quem, o quê, em que ordem), **o que dá errado por dentro** (tabelas, campos, SQL), **como protegemos, passo a passo** e **como testamos**. Horários e valores são exemplos.

---

## 1. E se a resposta atrasada do parceiro e o job de reconciliação tentarem fechar o **mesmo** pagamento `PENDENTE` ao mesmo tempo, um como `APROVADO` e o outro como `EXPIRADO`?

### A história

Black Friday, 22h. A Ana compra um fone de **R$ 450**. A T1 cria o pagamento `PG-10` (`PENDENTE`, `version = 3`) e a reserva `R-10` (`ATIVA`); o estoque do fone cai de 5 para 4.

| Hora | O que acontece |
|---|---|
| 22:00:00 | O Checkout chama o parceiro. Passam 2 s sem resposta: timeout. `PG-10` fica `PENDENTE` e a Ana recebe `202`. |
| 22:05:00.000 | O job de reconciliação acha `PG-10` `PENDENTE` há mais de 5 min. Consulta o parceiro, que responde "não encontrei essa cobrança" (ainda não tinha processado). O job decide `EXPIRADO` e lê `PG-10` com `version = 3`. |
| 22:05:00.010 | No mesmo instante, a resposta atrasada do parceiro chega a outra réplica: "**aprovado**". Essa réplica também lê `PG-10` com `version = 3` e decide `APROVADO`. |
| 22:05:00.020 | As duas tentam gravar. |

### O que dá errado por dentro

Sem proteção, as duas gravações seguem o padrão "lê, decide no Java, grava":

```sql
UPDATE pagamento SET status = 'APROVADO' WHERE id = 'PG-10';  -- réplica
UPDATE pagamento SET status = 'EXPIRADO' WHERE id = 'PG-10';  -- job
```

**Quem grava por último vence** (a "atualização perdida"). Se o job gravar depois, `PG-10` fica `EXPIRADO`, e na mesma T2 o job libera a reserva e devolve a unidade (estoque volta a 5). Resultado: a Ana **foi cobrada**, o pedido aparece expirado, e o fone dela foi revendido para outra pessoa. Se for o contrário (a réplica grava por último), o pagamento aparece `APROVADO` depois de o estoque já ter sido devolvido.

### Como protegemos, passo a passo

1. **Lock otimista com `@Version`.** O Hibernate não grava só "status = X". Ele grava:
   ```sql
   UPDATE pagamento SET status = 'APROVADO', version = 4
    WHERE id = 'PG-10' AND version = 3;
   ```
2. **O primeiro commit vence.** Digamos que a réplica (com `APROVADO`) faz commit às 22:05:00.025. Agora `PG-10` tem `version = 4`. Na mesma T2, a reserva `R-10` vira `CONFIRMADA` (`WHERE status = 'ATIVA'`).
3. **O segundo não grava nada.** O `UPDATE ... WHERE version = 3` do job afeta **0 linhas**. O Hibernate lança `OptimisticLockException` e a T2 do job inteira volta atrás (`ROLLBACK`), **incluindo** a liberação da reserva e a devolução da unidade, que estavam na mesma transação.
4. **Quem perde relê, não repete às cegas.** O job relê `PG-10`: está `APROVADO`, não é mais `PENDENTE`. Ele não faz nada além de registrar no log.
5. **Segunda trava no próprio SQL:** a gravação também tem `AND status = 'PENDENTE'`. Mesmo um caminho sem JPA (um script, por exemplo) não reabre um pagamento já fechado.
6. **E se o job tivesse vencido?** `PG-10` ficaria `EXPIRADO` e a reserva `LIBERADA`, e a resposta "aprovado" chegaria a um pagamento fechado. ⚠️ **Lacuna do desenho atual:** o `system-design.md` da Aula 3 diz que a resposta atrasada "não faz nada", mas não diz o que fazer com um dinheiro que **entrou** num pagamento `EXPIRADO`. O certo seria pedir o **estorno** automático (como a Aula 4 faz no risco 1) ou alertar a equipe. Registrado aqui sem mudar o desenho.

### Como testamos

Teste de integração com Testcontainers (PostgreSQL 17 de verdade, não H2):

1. Criar `PG-10` como `PENDENTE`, `version = 3`, com uma reserva `ATIVA` e o estoque em 4.
2. Abrir duas threads: uma fecha como `APROVADO`, a outra como `EXPIRADO`. Um `CountDownLatch` (um "portão" que solta as duas no mesmo instante) garante que as duas leiam `version = 3` antes de qualquer uma gravar.
3. **Conferir:** exatamente uma termina bem e a outra recebe `ObjectOptimisticLockingFailureException`; o status final é o da vencedora; a reserva mudou de `ATIVA` **uma vez**; o estoque é 4 se a vencedora foi `APROVADO` ou 5 se foi `EXPIRADO`, nunca 6.
4. Repetir 50 vezes: em nenhuma rodada o estoque fica errado.
5. **Contraprova:** remover o `@Version`; o teste tem de ficar vermelho em parte das rodadas.

---

## 2. E se o pagamento for recusado ou expirar **depois** que a última unidade do produto já foi reservada para ele?

### A história

Black Friday, 23h. Resta **1** unidade de uma TV em promoção (R$ 2.000). O Bruno chega primeiro.

| Hora | O que acontece |
|---|---|
| 23:00:00.000 | T1 do Bruno: chave, pedido `P-20`, pagamento `PG-20` (`PENDENTE`), reserva `R-20` (`ATIVA`, `expira_em = 23:15`) e, por último, o estoque cai de 1 para **0**. Commit. |
| 23:00:00.100 | A Carla tenta comprar a mesma TV. O `UPDATE estoque ... WHERE disponivel >= 1` afeta 0 linhas: `409`, "esgotado". |
| 23:00:01.500 | O parceiro **recusa** o cartão do Bruno (sem limite). |
| 23:00:01.510 | O Checkout tenta a T2, e nesse instante a réplica do Bruno cai (reinício por falta de memória). |

### O que dá errado por dentro

Dois perigos:

- **A unidade presa:** se nada liberar `R-20`, o estoque fica em 0 para sempre. A TV está na prateleira, mas a loja mostra "esgotado" a noite toda: venda perdida em plena Black Friday.
- **Devolver duas vezes:** a liberação pode ser tentada por mais de um caminho (a T2 refeita depois do reinício, o job de reconciliação, o vencimento da reserva). Se cada um fizer `UPDATE estoque SET disponivel = disponivel + 1` sem olhar o estado da reserva, o estoque vai para **2**, e a loja vende uma TV que não existe.

### Como protegemos, passo a passo

1. **Fechar o pagamento e liberar a reserva na mesma transação (T2).** O caminho normal é:
   ```sql
   UPDATE pagamento SET status = 'RECUSADO', version = version + 1
    WHERE id = 'PG-20' AND version = :v AND status = 'PENDENTE';
   UPDATE reserva SET status = 'LIBERADA'
    WHERE id = 'R-20' AND status = 'ATIVA';
   -- só se a linha acima mudou (1 linha):
   UPDATE estoque SET disponivel = disponivel + 1 WHERE sku = 'TV';
   ```
   Ou tudo isso grava junto, ou nada grava.
2. **Se a réplica cair antes da T2** (o caso do Bruno), nada foi gravado: `PG-20` continua `PENDENTE` e `R-20` continua `ATIVA`. Não há meio-termo.
3. **A reconciliação assume.** Ela consulta o parceiro pela chave, recebe "recusado" e faz a mesma T2. Se o parceiro não souber de nada, ela espera o `expira_em` (23:15) e fecha como `EXPIRADO`, também com a mesma T2.
4. **Devolver duas vezes é impossível.** A devolução da unidade depende de a reserva ter mudado de `ATIVA` para `LIBERADA`. O segundo caminho que tentar encontra `LIBERADA`, afeta 0 linhas e não devolve nada. O estoque volta a **1**, nunca a 2.
5. **A Carla pode comprar.** Com o estoque em 1, uma nova tentativa da Carla passa no `UPDATE` atômico.
6. **Escolha de negócio (cartão recusado encerra o pedido):** o Bruno, para tentar outro cartão, começa um checkout novo, com chave e pedido novos, e disputa a TV de novo. A unidade do pedido recusado já voltou para todos.

⚠️ **Ponto a decidir:** o desenho deixa a unidade presa até a reconciliação rodar, o que pode levar até 15 min (o `expira_em`) se o parceiro não responder. Na Black Friday, um prazo de reserva menor para produtos em promoção (ex.: 5 min) reduziria a venda perdida. O `system-design.md` não fixa esse valor.

### Como testamos

Testcontainers com PostgreSQL 17 e WireMock no lugar do parceiro:

1. Estoque da TV = 1. O Bruno compra (WireMock configurado para **recusar**).
2. **Conferir:** `PG-20` `RECUSADO`, `R-20` `LIBERADA`, estoque = 1; o Bruno recebeu `402`.
3. **Liberação repetida:** chamar a liberação mais duas vezes (simulando a reconciliação e o vencimento).
   **Conferir:** estoque continua 1; a reserva mudou de estado uma vez só.
4. **Queda no meio:** forçar uma exceção logo antes da T2 (um "ponto de falha" ativado só no teste).
   **Conferir:** `PG-20` `PENDENTE`, `R-20` `ATIVA`, estoque = 0. Rodar a reconciliação com o WireMock respondendo "recusado": estoque volta a 1.
5. **Disputa depois da liberação:** a Carla compra em seguida e recebe `201`; estoque = 0, nunca negativo (o `CHECK (disponivel >= 0)` derrubaria qualquer erro).

---

## 3. E se a migration que cria o índice para resolver o incidente rodar **durante o pico** e travar as gravações da tabela de pedidos?

Contexto: **migration** é um script versionado que muda a estrutura do banco (aqui, com o Flyway, que roda os scripts no deploy). O índice que resolve o incidente é o composto `(customer_id, order_status)` na tabela `pedidos`.

### A história

Black Friday, 21h. O incidente do pool está acontecendo. Alguém decide corrigir na hora.

| Hora | O que acontece |
|---|---|
| 21:00:00 | Deploy com a migration `V7__indice_pedidos.sql`: `CREATE INDEX idx_pedidos_cliente_status ON pedidos (customer_id, order_status);`. A tabela tem 40 milhões de linhas. |
| 21:00:01 | O PostgreSQL começa a construir o índice e pega um lock na tabela `pedidos` que **bloqueia `INSERT`, `UPDATE` e `DELETE`** até terminar. |
| 21:00:02 | Toda T1 de checkout tenta `INSERT INTO pedidos` e fica **esperando**. Cada uma segura uma conexão do pool. |
| 21:00:05 | As 50 conexões do pool estão presas esperando. Novas compras esperam o tempo máximo por uma conexão (`connection-timeout: 2 s`) e recebem `503`. |
| 21:04:00 | O índice termina. Foram **4 minutos sem nenhum pedido gravado** em plena Black Friday. |

### O que dá errado por dentro

O `CREATE INDEX` comum pega um lock do tipo `SHARE` na tabela: leituras continuam, mas qualquer escrita espera. O tempo do lock é o tempo de ler os 40 milhões de linhas e montar o índice. O remédio do incidente vira um incidente pior: antes o checkout estava lento, agora **parou**.

### Como protegemos, passo a passo

1. **`CREATE INDEX CONCURRENTLY`.** A migration passa a ser:
   ```sql
   CREATE INDEX CONCURRENTLY idx_pedidos_cliente_status
       ON pedidos (customer_id, order_status);
   ```
   Ela **não bloqueia** `INSERT`/`UPDATE`/`DELETE`. O preço: é mais lenta (lê a tabela duas vezes e espera as transações em andamento terminarem).
2. **Fora de transação.** O `CONCURRENTLY` não pode rodar dentro de uma transação. No Flyway, o script é marcado como não transacional (`executeInTransaction=false` no arquivo de configuração do script).
3. **Se falhar no meio** (deploy cancelado, conexão caída), sobra um índice marcado como **inválido**: ele ocupa espaço e pesa nas escritas, mas não ajuda nas consultas. O deploy confere `SELECT indisvalid FROM pg_index ...`; se for `false`, faz `DROP INDEX CONCURRENTLY` e cria de novo.
4. **Antes da Black Friday, nunca no pico.** A migration roda dias antes, num horário de pouco movimento. Antes disso, o índice é provado com `EXPLAIN (ANALYZE, BUFFERS)` num banco com volume de produção (o plano de diagnóstico da seção 3.1 do desenho).
5. **No pico, o que se faz é conter:** o Bulkhead por produto e o `connection-timeout` curto já limitam o estrago do incidente sem mexer na estrutura do banco.

### Como testamos

Testcontainers com PostgreSQL 17:

1. Carregar a tabela `pedidos` com volume suficiente para o índice levar alguns segundos (ex.: 2 milhões de linhas).
2. Numa thread, rodar a migration com `CONCURRENTLY`. Em outra, fazer `INSERT INTO pedidos` a cada 10 ms, medindo o tempo de cada um.
3. **Conferir:** nenhum `INSERT` demorou mais que 100 ms durante a criação do índice; ao fim, `indisvalid = true`; o `EXPLAIN` da consulta de pedidos por cliente e status usa o índice (sem `Seq Scan` em `pedidos`).
4. **Contraprova:** rodar o mesmo teste com `CREATE INDEX` comum; os `INSERT` ficam parados enquanto o índice é criado, e o teste fica vermelho.
5. **Falha no meio:** cancelar a criação do índice (`pg_cancel_backend`) e rodar o passo de conferência do deploy.
   **Conferir:** o índice inválido é removido e criado de novo.
