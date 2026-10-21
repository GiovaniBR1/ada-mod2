# Perguntas de Risco: Aula 03 - Banco de Dados do Checkout

Material extra: o template da Aula 03 não pede estas perguntas. Elas reforçam o `system-design.md`.

O desenho usa duas transações curtas (a chamada ao parceiro fica no meio, fora de transação), lock otimista no pagamento, `UPDATE` condicional no estoque e reserva que muda uma vez só. A regra: estoque nunca negativo, pagamento fecha uma vez, falha nunca vira aprovado.

Cada pergunta responde direto, depois o porquê, depois um exemplo e o teste.

---

## 1. E se a resposta atrasada do parceiro e o job fecharem o mesmo pagamento `PENDENTE` ao mesmo tempo, um como `APROVADO` e o outro como `EXPIRADO`?

**Resposta.** Um commit vence. O outro altera 0 linhas, a transação inteira volta atrás, e quem perdeu relê em vez de gravar de novo.

**Por quê.** "Lê, decide no Java, grava" deixa o último a escrever por cima do primeiro. Se o job gravar `EXPIRADO` depois de um `APROVADO`, devolve a unidade de um cliente que foi cobrado. O `@Version` vira `UPDATE ... WHERE id = ? AND version = ?`. Zero linhas lançam `OptimisticLockException` e desfazem a liberação de estoque que estava na mesma transação. A cláusula `AND status = 'PENDENTE'` segura também um caminho sem JPA.

**Exemplo.** Pagamento `PG-10`, `PENDENTE`, `version = 3`, reserva `ATIVA`, estoque 4. Job e réplica leem a versão 3. A réplica commita `APROVADO` e `version = 4`, reserva `CONFIRMADA`. O `UPDATE` do job com `version = 3` afeta 0 linhas. O estoque continua 4. Se o job tivesse vencido, o pagamento ficaria `EXPIRADO` e a linha não reabriria: dinheiro que entrou depois segue o estorno da saga (apêndice da Aula 4), não um segundo status.

**Como testamos.** Testcontainers com PostgreSQL 17.

1. Criar `PG-10` como acima.
2. Duas threads, soltas juntas, fecham como `APROVADO` e `EXPIRADO`.
3. Uma termina bem, a outra recebe `ObjectOptimisticLockingFailureException`. A reserva muda de `ATIVA` uma vez. Estoque 4 se venceu `APROVADO`, 5 se venceu `EXPIRADO`, nunca 6.
4. Cinquenta rodadas. Contraprova: sem `@Version`, o teste fica vermelho em parte delas.

---

## 2. E se o pagamento for recusado ou expirar depois que a última unidade já foi reservada?

**Resposta.** A unidade volta uma vez. A devolução só acontece se a reserva ainda estiver `ATIVA`.

**Por quê.** Sem a liberação, o estoque fica em 0 com a unidade na prateleira. Com `UPDATE estoque SET disponivel = disponivel + 1` sem olhar a reserva, dois caminhos (a transação refeita, o job, o vencimento) devolvem duas vezes e o estoque passa de 1 para 2. A T2 fecha o pagamento e libera a reserva na mesma transação. A devolução da unidade depende de o `UPDATE` da reserva ter mudado 1 linha.

**Exemplo.** Estoque da TV = 1. A primeira compra reserva e o parceiro recusa. T2 marca `RECUSADO`, reserva `LIBERADA`, estoque volta a 1. Uma segunda liberação encontra `LIBERADA`, afeta 0 linhas e não soma de novo. A compra seguinte consegue a unidade. Se a réplica cair antes da T2, nada foi gravado: pagamento `PENDENTE`, reserva `ATIVA`, estoque 0, e a reconciliação faz a mesma T2.

**Como testamos.** PostgreSQL 17 e WireMock.

1. Estoque 1, parceiro recusa. Pagamento `RECUSADO`, reserva `LIBERADA`, estoque 1, resposta `402`.
2. Liberar mais duas vezes. Estoque continua 1.
3. Exceção antes da T2: continua `PENDENTE` e `ATIVA`, estoque 0. A reconciliação com "recusado" devolve a 1.
4. A compra seguinte recebe `201`. Estoque 0, nunca negativo (`CHECK (disponivel >= 0)`).

Cartão recusado encerra aquele pedido. Outro cartão é outro checkout, com chave nova, e disputa a unidade de novo.

---

## 3. E se a migration do índice do incidente rodar durante o pico e travar as gravações?

**Resposta.** O índice entra com `CREATE INDEX CONCURRENTLY`, fora de transação, antes da Black Friday. `CREATE INDEX` comum segura a escrita da tabela inteira.

**Por quê.** O comando comum pega lock `SHARE`: leitura segue, `INSERT`/`UPDATE`/`DELETE` esperam até o índice terminar. Numa tabela grande, essa espera esgota o pool. O `CONCURRENTLY` não bloqueia escrita. O preço é ser mais lento e não poder rodar dentro de transação. No Flyway, o script fica com `executeInTransaction=false`. Se a criação cair no meio, sobra índice `INVALID`: o deploy lê `indisvalid` e, se for falso, faz `DROP INDEX CONCURRENTLY` e cria de novo.

**Exemplo.** Tabela de 40 milhões de linhas, `CREATE INDEX` comum no pico. Em segundos as conexões do pool estão presas no `INSERT` do pedido, e o checkout responde `503` até o índice acabar. Com `CONCURRENTLY` num horário de pouco movimento, os `INSERT` continuam. O índice do incidente é o composto `(customer_id, order_status)`, provado com o `EXPLAIN` da seção 3.1 do desenho. O plano de 6,5 ms → 0,06 ms do laboratório é outra consulta, só por `customer_id`.

**Como testamos.** PostgreSQL 17.

1. Carregar `pedidos` o bastante para o índice levar alguns segundos (exemplo: 2 milhões de linhas).
2. Numa thread, a migration com `CONCURRENTLY`. Na outra, `INSERT` a cada 10 ms.
3. Nenhum `INSERT` passa de 100 ms. No fim, `indisvalid = true` e o `EXPLAIN` da consulta por cliente e status usa o índice composto, sem `Seq Scan` em `pedidos`.
4. Contraprova: `CREATE INDEX` comum. Os `INSERT` param e o teste fica vermelho.
5. `pg_cancel_backend` no meio. O passo do deploy remove o índice inválido e cria de novo.
