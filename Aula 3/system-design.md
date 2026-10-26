# System Design: Aula 03 - Banco de Dados em Larga Escala

> **Cenário:** Black Friday. Dinheiro é ACID (grava tudo ou nada). Catálogo pode atrasar. Estoque: muita gente na última unidade. p99 em 4,8 s (99 em 100 foram mais rápidas) e pool cheio. A consulta usa `customer_id` e `order_status = 'processing'`.

Aqui entram JPA (a biblioteca Java que mapeia objeto em tabela), PostgreSQL 17 e Flyway (mudanças de tabela).

Tabelas: [anexo](anexo-detalhes.md). Linha de pagamento: [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento).

## 1. Decisão de Armazenamento e Chave Primária (PK)

### 1.1 Motor de Banco de Dados e Paradigma de Persistência

- **A Escolha para o Checkout:** ( ) Relacional Puro (PostgreSQL) (X) Persistência Poliglota / Híbrida
- **Justificativa Arquitetural:**
  > O núcleo da compra é PostgreSQL, com ACID. Idempotência: repetir o pedido não cobra duas vezes. Chave, reserva, pedido e pagamento nascem juntos.
  > Se a rede partir, um 503 é melhor do que vender a mesma unidade duas vezes.
  > Busca, carrinho e telemetria aceitam BASE. BASE: a leitura pode atrasar, e isso não move dinheiro.
  > Quem acabou de pagar lê o banco primário. O preço cobrado é a coluna do Postgres. [Fronteira](anexo-detalhes.md#fronteira).
- **Além do Modelo Documental (Ecossistema NoSQL):**
  > Redis guarda carrinho e sessão, com prazo para sumir. OpenSearch busca o produto pelo nome.
  > TimescaleDB ou Prometheus guardam o p99 e o uso do pool. Neo4j liga contas para achar fraude, fora do clique de comprar.
  > Nenhuma delas move dinheiro. O preço na busca pode atrasar. O valor cobrado é `preco`. [NoSQL](anexo-detalhes.md#nosql).

### 1.2 Estratégia da Chave Primária

- **A Escolha:** ( ) Sequence / Identity ( ) UUIDv4 (X) UUIDv7 / TSID
- **Impacto no Armazenamento:**
  > A chave é UUIDv7, gerada no Java, em `uuid` de 16 bytes. UUIDv7: um id que cresce com o tempo.
  > Sequence pede um contador central. `IDENTITY` desliga o lote do Hibernate. UUIDv4 espalha o `INSERT` e parte a página da B-Tree no meio. B-Tree: a árvore ordenada do índice.
  > Em 300 mil linhas, o índice da chave aleatória em texto ficou em 22 MB; o da chave sequencial, em 6,6 MB. Parte da diferença é o tamanho do texto. O v7 cresce à direita, e o id existe antes do `INSERT`.
  > O v7 mostra o horário de criação. Quem não é dono do pedido recebe 404. A Idempotency-Key segue o UUID do cliente, por 24 h. [Chave](anexo-detalhes.md#chave).

### 1.3 Modelo de Dados (campos principais)

- **Tabelas da compra:**
  > Id é `uuid` v7. Dinheiro é `numeric(12,2)`. Status é `text` com `CHECK` (o banco recusa valor fora da lista).
  > A chave de idempotência tem `UNIQUE (chave)` em `idempotency_keys`. O pagamento tem `UNIQUE (order_id)`.
  > `pagamento.status`: `PENDENTE`, `AGUARDANDO_CAPTURA`, `APROVADO`, `RECUSADO`, `EXPIRADO`. É o cartão síncrono, antes do Pix com QR.
  > `order_status` nesta aula: `PENDENTE` e `PAGAMENTO_EM_VERIFICACAO`. `processing` e `delivered` são o valor antigo do exercício, só na consulta do incidente.
  > A mesma coluna depois se chama `status` e ganha os doze estados do [apêndice](../Aula%204/apendice-maquina-de-estados.md). Uma coluna só. [Modelo](anexo-detalhes.md#modelo).

## 2. A Dualidade da Concorrência: Locks e Mitigações

### 2.1 Mudança de Status do Pagamento (Baixa contenção por registro)

- **Abordagem de Lock:** (X) Lock Otimista (`@Version`) ou ( ) Lock Pessimista (`SELECT FOR UPDATE`)?
- **Justificativa:**
  > A escolha é lock otimista (`@Version`): não trava a linha, confere a versão ao gravar.
  > Zero linhas viram `OptimisticLockException`. Quem perde relê e não reabre o que fechou.
  > `AND status = 'PENDENTE'` segura o caminho normal. Esse `PENDENTE` é só timeout ou 500 na linha de pagamento.
  > A exceção do Pix atrasado está na [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento). [Pagamento](anexo-detalhes.md#pagamento).

### 2.2 Controle de Estoque Concorrente (Alta contenção no mesmo registro)

- **Abordagem de Lock:** ( ) Lock Otimista (`@Version`) ou (X) Lock pessimista pelo `UPDATE` condicional, não por `SELECT FOR UPDATE`?
- **Justificativa:**
  > A escolha é um `UPDATE` condicional no fim da primeira transação. A leitura não trava a linha.
  > Só baixa se ainda houver unidade. Zero linhas viram 409. `CHECK (disponivel >= 0)` é a segunda trava. A chamada ao parceiro fica fora da transação.
  > Essa transação grava a chave, o pedido `PENDENTE` e a reserva `ATIVA`, e então baixa o estoque. Não grava pagamento.
  > A seguinte grava o pagamento: `APROVADO` ou `RECUSADO`. Timeout ou 500 deixam a linha `PENDENTE` e o pedido em `PAGAMENTO_EM_VERIFICACAO`. 503 sem chamada não cria linha.
  > Os 4 ms são hipótese, não uma medida: 1000 ÷ 4 ms ≈ 250 compras/s num produto. Bulkhead de 8 vagas por réplica devolve 503 em 50 ms. [Estoque](anexo-detalhes.md#estoque).

### 2.3 Riscos e Mitigações

- **Análise Crítica:**
  > Quem perde relê o estado. No estoque, a transação é curta, o `lock_timeout` é 2 s e o bulkhead age antes do pool.
  > Deadlock: gravamos os produtos em ordem crescente. O Postgres aborta um dos dois (`40P01`). Essa transação repete uma vez.
  > A reserva tem `expira_em`. Se o pagamento não fecha, a unidade volta. [Riscos](anexo-detalhes.md#riscos).

## 3. Troubleshooting do Incidente e Gestão de Índices

### 3.1 Investigação de Incidente em Produção

- **Plano de Diagnóstico:**
  > Primeiro provo a consulta. Nenhum índice entra sem plano antes e depois.
  > Olho o pool, quem espera e as consultas mais caras. Uso `EXPLAIN (ANALYZE, BUFFERS)`. Esse comando executa a consulta. `UPDATE` só dentro de `ROLLBACK`.
  > No laboratório, a consulta só com `customer_id` caiu de 6,5 ms para 0,06 ms. O incidente pede o índice `(customer_id, order_status)`.
  > `processing` aqui é valor antigo do exercício, não estado do checkout. O plano do anexo é um desenho no PostgreSQL 17, não uma medida. [Diagnóstico](anexo-detalhes.md#diagnostico).

### 3.2 Decisão de Engenharia: A Abordagem de Índices

- **Dilema no Pull Request:** um índice em cada coluna de filtro.
- **Seu Posicionamento:**
  > Não aprovo. O filtro usa os dois campos juntos. O índice é `(customer_id, order_status)`.
  > `delivered` é valor antigo do exercício, cerca de 90% das linhas. Sozinho, lê a tabela inteira.
  > Cada índice a mais custa escrita. O HOT update (trocar a linha sem reescrever o índice) se perde só quando muda coluna indexada. Índice numa coluna que ninguém atualiza não bloqueia o HOT.
  > No laboratório, os índices somavam 13 MB contra 12 MB da tabela. O PR exige `EXPLAIN` antes e depois, e `CREATE INDEX CONCURRENTLY`. Índice sem uso sai. [Índices](anexo-detalhes.md#indices).

## 4. Os Edge Cases (Engenharia do Caos)

1. **E se...** a resposta atrasada e a reconciliação fecharem o mesmo `PENDENTE` com resultados diferentes?
   - **Resposta do Sistema:** Os dois leem a versão 3. O primeiro grava a versão 4. O segundo toma `OptimisticLockException` e para. A exceção do Pix atrasado está na [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento).
2. **E se...** o pagamento for recusado ou expirar com a última unidade já reservada?
   - **Resposta do Sistema:** Grava `RECUSADO` ou `EXPIRADO` e libera a reserva se ela está `ATIVA`. Sem linha, a reserva espera a consulta. Cobrança sem linha: a reconciliação insere. Linha `PENDENTE`: só fecha.
3. **E se...** a migration do índice travar as gravações no pico?
   - **Resposta do Sistema:** Nasce com `CREATE INDEX CONCURRENTLY`, fora de transação, antes do pico. Índice `INVALID` é recriado. [Diagrama](anexo-detalhes.md#diagrama).

## 5. Testando a Persistência

> O teste sobe PostgreSQL 17 com Testcontainers. H2 não prova lock nem plano.
> 200 threads e 5 unidades dão 5 respostas 201, 195 respostas 409 e saldo zero. [Testes](anexo-detalhes.md#testes).
