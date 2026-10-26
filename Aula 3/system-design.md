# Template de System Design: Aula 03 - Banco de Dados em Larga Escala

> **Contexto do Cenário: Checkout na Black Friday**
>
> Sua equipe está preparando a persistência da API de Checkout para o pico de tráfego da Black Friday:
>
> 1. **Armazenamento:** Transações financeiras de pedidos exigem garantias ACID rigorosas e consistência imediata, enquanto dados de catálogo e sessões de usuários demandam alta vazão de leitura e tolerância a partição.
> 2. **Concorrência:** A atualização de status do pedido tem baixa disputa por registro (apenas o comprador interage), mas a **reserva de estoque** sofre altíssima contenção com centenas de clientes disputando as últimas unidades do mesmo SKU promocional.
> 3. **Incidente em Produção:** Em testes de carga, o tempo de resposta p99 subiu para 4.8s e o pool HikariCP saturou devido a consultas lentas de pedidos (`WHERE customer_id = ?` e `WHERE order_status = 'processing'`).
>
> **Instruções:** Responda às decisões arquiteturais abaixo fundamentando suas escolhas estritamente com base nos requisitos e restrições deste cenário. Adicione este bloco ao repositório contínuo do seu projeto.
>
> **Continuidade com a Aula 02.** É o mesmo checkout da Aula 2. Nenhuma decisão da Aula 02 muda; aqui entra só a camada de dados. Stack: Spring Data JPA (Hibernate 6), PostgreSQL 17, Flyway, pool de conexões com o banco.

Tabelas, SQL, planos e o diagrama estão no [anexo](anexo-detalhes.md). A linha de pagamento segue a [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento).

---

## 1. Decisão de Armazenamento e Chave Primária (PK)

### 1.1 Motor de Banco de Dados e Paradigma de Persistência

- **A Escolha para o Checkout:** ( ) Relacional Puro (PostgreSQL) (X) Persistência Poliglota / Híbrida
- **Justificativa Arquitetural:** Avalie o Teorema CAP e o modelo de consistência (ACID vs BASE). Por que a sua escolha atende aos requisitos do Checkout sem comprometer a integridade financeira?
  > Decisão: o núcleo da compra é PostgreSQL, ACID. Chave de idempotência, reserva, pedido e pagamento só existem juntos.
  > Por quê: numa partição, dinheiro e estoque escolhem consistência. É melhor `503` do que vender a mesma unidade duas vezes. Busca, carrinho e telemetria aceitam BASE. O preço cobrado sai do Postgres na hora da compra, não do índice de busca.
  > Exemplo: quem acabou de pagar lê o pedido no primário. Histórico antigo pode ir para a réplica, com segundos de atraso. Pedido em banco documental foi descartado: o gargalo do incidente é índice e concorrência.
  > Tabela: [anexo, fronteira](anexo-detalhes.md#fronteira).
- **Além do Modelo Documental (Ecossistema NoSQL):** A persistência poliglota vai muito além da dicotomia "Relacional vs MongoDB". Considerando as necessidades complementares de uma plataforma de e-commerce (como carrinhos efêmeros, busca textual de produtos, telemetria/logs, auditoria temporal ou análise de conexões/fraude), cite e comente pelo menos outros dois tipos/famílias de bancos NoSQL que poderiam coexistir na arquitetura, indicando para quais casos de uso específicos cada um seria a ferramenta ideal e por quê.
  > Decisão: quatro famílias, nenhuma no caminho do dinheiro. Redis (chave-valor) para carrinho e sessão com TTL. OpenSearch para busca por nome. TimescaleDB ou Prometheus para p99 e pool. Neo4j para antifraude, fora do clique.
  > Por quê: cada uma cobre uma leitura que o Postgres faz mal. Idempotência e o disjuntor continuam no `UNIQUE` do Postgres (Aula 2).
  > Exemplo: o preço na busca pode atrasar segundos; o cobrado é o `preco` do catálogo. Catálogo variável cabe em `jsonb` até a dor aparecer.
  > Tabela: [anexo, NoSQL](anexo-detalhes.md#nosql).

### 1.2 Estratégia da Chave Primária

- **A Escolha:** ( ) Sequence / Identity ( ) UUIDv4 (X) UUIDv7 / TSID
- **Impacto no Armazenamento:** Por que essa estratégia foi escolhida considerando inserções concorrentes e o impacto de fragmentação na árvore B-Tree (page splits)?
  > Decisão: UUIDv7 gerado no Java, coluna `uuid` de 16 bytes.
  > Por quê: sequence exige contador central, e `IDENTITY` desliga o batch do Hibernate. UUIDv4 espalha o `INSERT` e divide a página no meio da B-Tree.
  > Exemplo, 300 mil linhas: PK aleatória, índice de 22 MB, 70% de densidade, 1,3 s; bigint sequencial, 6,6 MB, 90%, 0,5 s. O v7 cai à direita, como a sequence, e o id existe antes do `INSERT`.
  > O v7 revela o horário; o dono é conferido à parte (`404`). A Idempotency-Key segue UUID do cliente, `UNIQUE`, por 24 h. Medida: [anexo, chave](anexo-detalhes.md#chave).

### 1.3 Modelo de Dados (campos principais)

> Material extra: mostra onde moram as travas. Campos completos no [anexo, modelo](anexo-detalhes.md#modelo).

- **Tabelas da compra:**
  > Decisão: `uuid` v7, dinheiro `numeric(12,2)`, status `text` com `CHECK`. Duas travas: `UNIQUE (chave)` e `UNIQUE (order_id)` no pagamento.
  > O `CHECK` do pagamento é `PENDENTE`, `AGUARDANDO_CAPTURA`, `APROVADO`, `RECUSADO`, `EXPIRADO`, o mesmo das Aulas 2 e 4. Este desenho é o caminho síncrono, antes do Pix com QR.
  > A reserva nasce `ATIVA` na T1 e só sai uma vez. Cartão recusado encerra o pedido: outro cartão é checkout novo.
  > `order_status` (exemplo `processing`/`delivered`) é o nome da coluna do exercício do incidente. A partir da Aula 4 ela passa a se chamar `status` e o CHECK passa a ter os doze estados do [apêndice](../Aula%204/apendice-maquina-de-estados.md). Não são duas colunas. Diagrama: [anexo](anexo-detalhes.md#diagrama).

---

## 2. A Dualidade da Concorrência: Locks e Mitigações

### 2.1 Mudança de Status do Pagamento (Baixa contenção por registro)

- **Abordagem de Lock:** (X) Lock Otimista (`@Version`) ou ( ) Lock Pessimista (`SELECT FOR UPDATE`)?
- **Justificativa:** Por que essa abordagem é a mais adequada para este cenário específico?
  > Decisão: lock otimista, `@Version`.
  > Por quê: um pagamento quase não é disputado. `FOR UPDATE` em toda mudança pagaria fila por um conflito raro. Zero linhas no `UPDATE` da versão viram `OptimisticLockException`.
  > Exemplo: a resposta tardia e o job fecham o mesmo `PENDENTE`. Quem perde relê e não reabre. A cláusula `AND status = 'PENDENTE'` segura o caminho sem JPA.
  > A única exceção é a [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento): webhook com a linha em `EXPIRADO` grava `APROVADO`. Código: [anexo, pagamento](anexo-detalhes.md#pagamento).

### 2.2 Controle de Estoque Concorrente (Alta contenção no mesmo registro)

- **Abordagem de Lock:** ( ) Lock Otimista (`@Version`) ou (X) Lock pessimista pelo `UPDATE` condicional, não por `SELECT FOR UPDATE`?
- **Justificativa:** Centenas de usuários disputando os últimos itens na Black Friday. Como você garante que o saldo nunca fique negativo sem derrubar o banco?
  > Decisão: `UPDATE` condicional no fim da T1. Não é `@Version` e não é `SELECT FOR UPDATE` como primeiro passo.
  > Por quê: no otimista, centenas leem a mesma versão e só um vence. `UPDATE estoque SET disponivel = disponivel - :qtd WHERE sku = :sku AND disponivel >= :qtd` trava a linha até o commit. Zero linhas viram `409`. O `CHECK (disponivel >= 0)` é a segunda trava. A chamada ao parceiro fica fora da transação.
  > T1 grava chave, pedido e reserva `ATIVA`, e por último baixa o estoque. Não grava pagamento. T2 grava o pagamento quando o parceiro responde: `APROVADO` ou `RECUSADO` se o resultado é claro; `PENDENTE` só no timeout ou 500. 503 sem chamada não cria linha.
  > Exemplo, X ≈ 4 ms de lock: cerca de 250 compras/s por produto (1000 ÷ 4). Bulkhead de 8 vagas por réplica devolve `503` em 50 ms, sem abrir conexão. Com 4 réplicas a espera fica em cerca de 130 ms.
  > Conta e SQL: [anexo, estoque](anexo-detalhes.md#estoque).

### 2.3 Riscos e Mitigações

- **Análise Crítica:** Quais os riscos operacionais das abordagens de lock que você escolheu (ex: excesso de `OptimisticLockException` frustrando o cliente vs `ConnectionTimeoutException` e *deadlocks* enfileirando threads no Lock Pessimista)? Como sua arquitetura mitiga esses riscos?
  > Decisão: cada risco tem um corte curto, sem retry cego.
  > Pagamento: quem perde o otimista relê o estado. Estoque: T1 curta, `lock_timeout` de 2 s, Bulkhead por produto antes do pool. Deadlock de carrinho: SKUs em ordem crescente; o Postgres aborta um com `40P01` e essa T1 repete uma vez. Reserva com `expira_em` devolve a unidade se o pagamento não fecha.
  > Exemplo: um produto quente usa no máximo 8 conexões por réplica, não o pool inteiro (ex.: 50). Baldes de estoque ficam de fora até o teste de carga mostrar que o `UPDATE` não aguenta.
  > Tabela: [anexo, riscos](anexo-detalhes.md#riscos).

---

## 3. Troubleshooting do Incidente e Gestão de Índices

### 3.1 Investigação de Incidente em Produção

- **Cenário:** Em testes de carga para a Black Friday, o tempo de resposta p99 do Checkout subiu repentinamente para 4.8s e o pool de conexões (HikariCP) saturou. Os logs apontam que a consulta de pedidos mais frequente (`WHERE customer_id = ? AND order_status = 'processing'`) está demorando segundos para responder.
- **Plano de Diagnóstico:** Qual é o seu processo técnico passo a passo para investigar a causa raiz dessa lentidão diretamente no banco de dados? Quais comandos, métricas ou planos você analisaria para comprovar a hipótese antes de propor qualquer mudança?
  > Decisão: provar a consulta antes de criar índice. Nenhum índice entra sem plano antes e depois.
  > Passos: métricas do Hikari (`active`, `pending`, `usage`); `pg_stat_activity` e `wait_event`; `pg_stat_statements` por `total_exec_time` e `mean_exec_time`; `EXPLAIN (ANALYZE, BUFFERS)`; `ANALYZE` se a estimativa mentir; repetir plano e carga depois do índice.
  > Exemplo do laboratório, outra consulta (só `customer_id`): `Seq Scan`, 1.539 buffers, 6,5 ms; depois, `Bitmap Index Scan`, 11 buffers, 0,06 ms. A consulta do incidente pede o composto `(customer_id, order_status)`. O plano do anexo é exemplo ilustrativo; rode o EXPLAIN no seu banco.
  > `EXPLAIN ANALYZE` executa; `UPDATE` só dentro de `ROLLBACK`. Planos: [anexo, diagnóstico](anexo-detalhes.md#diagnostico).

### 3.2 Decisão de Engenharia: A Abordagem de Índices

- **Dilema no Pull Request:** Para solucionar a lentidão, um desenvolvedor da equipe abriu um PR criando índices individuais para todas as colunas da tabela de pedidos que aparecem em filtros de consulta.
- **Seu Posicionamento:** Como responsável técnico, você aprovaria esse PR? Qual é a sua avaliação crítica sobre essa estratégia e quais impactos colaterais sistêmicos devem ser ponderados antes de aprovar novos índices?
  > Decisão: não aprovo o PR de um índice por coluna.
  > Por quê: a consulta usa `customer_id` e `order_status` juntos. O índice é o composto `(customer_id, order_status)`. `order_status` sozinho, com 90% `delivered`, vira `Seq Scan`. Cada índice a mais custa escrita e tira o HOT update.
  > Exemplo: no laboratório os índices de `olist_orders` já somavam 13 MB contra 12 MB da tabela. O PR precisa de `EXPLAIN` antes e depois e de `CREATE INDEX CONCURRENTLY`. Índice com `idx_scan = 0` sai.
  > Detalhe: [anexo, índices](anexo-detalhes.md#indices).

---

## 4. Os Edge Cases (Engenharia do Caos)

> Material extra. O texto completo está em `perguntas-de-risco.md`.

1. **E se...** a resposta atrasada do parceiro e a reconciliação fecharem o mesmo `PENDENTE` ao mesmo tempo, com resultados diferentes?
   - **Resposta do Sistema:** os dois leem `version = 3`. O primeiro grava `version = 4`; o segundo toma `OptimisticLockException`, relê e não muda o que já fechou. Se o vencedor foi `EXPIRADO` e o webhook de captura chega depois, `pagamento.status` vai de `EXPIRADO` para `APROVADO` e o pedido em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO`.
2. **E se...** o pagamento for recusado ou expirar depois que a última unidade já foi reservada?
   - **Resposta do Sistema:** a T2 grava `RECUSADO` ou `EXPIRADO` e, na mesma transação, libera a reserva só com `WHERE status = 'ATIVA'`. Cair antes da T2 não cria linha de pagamento; a reserva segue `ATIVA` até a reconciliação consultar o parceiro. Sem linha e com cobrança no parceiro, a reconciliação insere a linha (como a Aula 2). Com linha `PENDENTE`, só fecha.
3. **E se...** a migration do índice novo travar as gravações durante o pico?
   - **Resposta do Sistema:** `CREATE INDEX CONCURRENTLY`, fora de transação (`executeInTransaction=false`), antes da Black Friday. Índice `INVALID` é derrubado e recriado. O diagrama do caminho síncrono está no [anexo](anexo-detalhes.md#diagrama).

---

## 5. Testando a Persistência

> Material extra. Testcontainers com PostgreSQL 17; cada teste nasce vermelho. H2 não prova lock nem plano. A escala (o X, as vagas, o pool) sai do teste de carga.
>
> Exemplo: 200 threads e 5 unidades dão exatamente 5 respostas `201`, 195 respostas `409` e `disponivel = 0`. Tabela: [anexo, testes](anexo-detalhes.md#testes).
