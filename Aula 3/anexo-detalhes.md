# Anexo da Aula 3 — tabelas, contas e planos

O texto principal responde cada pergunta em poucas linhas. Aqui ficam as tabelas, o SQL, os planos e o diagrama. A regra da linha de pagamento é a [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento).

<a id="fronteira"></a>
## Fronteira de consistência

| Dado | Onde mora | Consistência | Por quê |
|---|---|---|---|
| Pedido, pagamento, estoque, `idempotency_keys` | PostgreSQL (primário) | ACID, imediata | Dinheiro e unidade física. |
| Status do pedido para o cliente logo após comprar | PostgreSQL **primário** | Imediata | Ler da réplica logo após gravar faz o cliente pagar e "não ver" o pedido. |
| Histórico antigo de pedidos, relatórios | Réplica de leitura | Eventual (segundos) | Tira carga do primário; atraso aceitável. |
| Catálogo para busca, carrinho, sessão, telemetria | NoSQL (abaixo) | Eventual | Alta vazão; perder ou atrasar não cobra ninguém. |

O que foi descartado: colocar pedidos num banco documental "para escalar". O gargalo do incidente é falta de índice e de controle de concorrência, não o modelo relacional. *Sharding* só quando a dor for real.

<a id="nosql"></a>
## Famílias NoSQL

| Família | Exemplo | Caso de uso | Por quê |
|---|---|---|---|
| Chave-valor em memória | Redis | Carrinho e sessão, com TTL | Leitura e escrita por chave em sub-milissegundo; o carrinho é efêmero e perder um não cobra ninguém. Não usamos Redis para a idempotência nem para o estado do Circuit Breaker (Aula 2: a trava é o `UNIQUE` do Postgres). |
| Busca textual | OpenSearch / Elasticsearch | Busca de produtos por nome, filtros, relevância | Índice invertido faz o que `LIKE '%fone%'` não faz. É alimentado de forma assíncrona; o preço exibido é informativo, o cobrado vem do Postgres. |
| Séries temporais | TimescaleDB, Prometheus | Telemetria: p99, uso do pool, taxa de `OptimisticLockException` | Grava muito, lê por janela de tempo, descarta dados velhos. |
| Grafo | Neo4j | Antifraude: mesmo cartão, dispositivo ou endereço ligando contas | Consultas de "quem está ligado a quem" em vários saltos, que em SQL viram muitos `JOIN`. Fica fora do caminho síncrono no início. |

Documental (MongoDB) também cabe para o catálogo com atributos variáveis por categoria, mas o Postgres com `jsonb` atende hoje.

<a id="chave"></a>
## Chave primária

Por que não Sequence/Identity. O auto-incremento depende de um contador central no banco: o ID só existe depois do `INSERT`. No Hibernate, `GenerationType.IDENTITY` desliga o JDBC batch insert. `SEQUENCE` com `allocationSize = 50` resolveria o batch, mas continua um contador central.

Por que não UUIDv4. O v4 é 100% aleatório. Cada `INSERT` cai num lugar qualquer da B-Tree: páginas no meio se dividem, o índice fragmenta. Medido no laboratório (`desafio/`): 300 mil linhas com PK aleatória (md5, equivalente ao v4) geraram um índice de 22 MB com 70% de densidade nas folhas e levaram 1,3 s; com bigint sequencial, 6,6 MB, 90% de densidade, 0,5 s. Parte do tamanho vem do texto de 32 caracteres; um `uuid` nativo ocupa 16 bytes.

UUIDv7: os primeiros 48 bits são o horário em milissegundos. O PostgreSQL 17 não tem `uuidv7()` nativa (chega no 18). O ID nasce no Java, `UuidCreator.getTimeOrderedEpoch()` (`uuid-creator`), antes do `persist`. O v7 revela o horário; o serviço confere o dono e pedido de outro cliente responde `404`.

A `Idempotency-Key` da Aula 2 não muda: UUID do cliente, coluna `UNIQUE`, não é a PK. A tabela é pequena (TTL de 24 h).

<a id="modelo"></a>
## Modelo

IDs são `uuid` v7; dinheiro é `numeric(12,2)`, nunca `float`; datas são `timestamptz`; status são `text` com `CHECK`.

| Tabela | Campos principais | Constraints e regras |
|---|---|---|
| `produto` | `sku text`, `nome text`, `preco numeric(12,2)`, `atributos jsonb` | PK `sku`; `CHECK (preco > 0)`. É daqui que sai o preço cobrado. |
| `estoque` | `sku text`, `disponivel int` | PK e FK `sku` → `produto`; `CHECK (disponivel >= 0)`. Sem `@Version`: a trava é o `UPDATE` atômico. |
| `pedidos` | `id uuid`, `customer_id uuid`, `order_status text`, `total numeric(12,2)`, `criado_em timestamptz`, `version bigint` | PK `id`; `customer_id NOT NULL`; `CHECK` na lista de `order_status` (ex.: `processing`, `delivered`); `@Version`. O `order_status` do exemplo é a consulta do incidente; o estado vivo da saga é o do [apêndice da Aula 4](../Aula%204/apendice-maquina-de-estados.md). |
| `pagamento` | `id uuid`, `order_id uuid`, `valor numeric(12,2)`, `status text`, `version bigint`, `criado_em timestamptz` | FK `order_id` → `pedidos` e `UNIQUE (order_id)`; `CHECK (status IN ('PENDENTE','AGUARDANDO_CAPTURA','APROVADO','RECUSADO','EXPIRADO'))`, o mesmo das Aulas 2 e 4; `@Version`. |
| `reserva` | `id uuid`, `order_id uuid`, `sku text`, `qtd int`, `status text`, `expira_em timestamptz` | FKs para `pedidos` e `estoque`; `UNIQUE (order_id, sku)`; `CHECK (qtd > 0)`; `CHECK (status IN ('ATIVA','CONFIRMADA','LIBERADA'))`; `expira_em NOT NULL`. |
| `idempotency_keys` | `id uuid`, `chave uuid`, `hash_do_corpo text`, `status text`, `order_id uuid`, `resposta_status int`, `resposta jsonb`, `travado_ate timestamptz`, `expira_em timestamptz` | PK `id`; `UNIQUE (chave)`; `CHECK (status IN ('EM_PROCESSAMENTO','CONCLUIDA'))`; `resposta` nula até concluir; `expira_em` = criação + 24 h. |

A reserva nasce `ATIVA` na T1, com `expira_em`. Só sai de `ATIVA`, e uma vez só: `APROVADO` → `CONFIRMADA`; `RECUSADO`, `EXPIRADO` ou prazo vencido → `LIBERADA`. Toda transição é `UPDATE ... WHERE status = 'ATIVA'`.

Cartão recusado encerra o pedido. Um `402` fica guardado: repetir a mesma chave devolve o mesmo `402`; outro cartão com a mesma chave muda o corpo e dá `422`. Outro cartão é checkout novo. Descartado por agora: várias tentativas no mesmo pedido (`UNIQUE` parcial em `order_id WHERE status IN ('PENDENTE','APROVADO')`).

<a id="pagamento"></a>
## Lock do pagamento

```java
@Entity
class Pagamento {
  @Id UUID id;                         // UUIDv7 gerado na aplicação
  @Version long version;
  @Enumerated(EnumType.STRING) StatusPagamento status;

  void fechar(StatusPagamento novo) {
    if (status != StatusPagamento.PENDENTE) return;   // caminho normal: ignora o que não é PENDENTE
    status = novo;
  }

  void capturaTardia() {
    // webhook de captura tardia: WHERE status = 'EXPIRADO' → APROVADO
    if (status != StatusPagamento.EXPIRADO) return;
    status = StatusPagamento.APROVADO;
  }
}
```

O Hibernate grava `UPDATE pagamento SET status = ?, version = version + 1 WHERE id = ? AND version = ?`. Zero linhas viram `OptimisticLockException` (`ObjectOptimisticLockingFailureException`). A cláusula `AND status = 'PENDENTE'` segura o caminho sem JPA. A única exceção é a regra única: webhook de captura com a linha em `EXPIRADO` grava `APROVADO`.

<a id="estoque"></a>
## Estoque

```sql
UPDATE estoque
   SET disponivel = disponivel - :qtd
 WHERE sku = :sku AND disponivel >= :qtd;
-- 1 linha afetada: reservou. 0 linhas: esgotado -> 409, nunca "OK".
```

O lock de linha só é solto no commit. Esse `UPDATE` é o último comando da T1. O `SELECT ... FOR UPDATE` explícito fica para quando a regra não cabe num `UPDATE`.

1. T1 (milissegundos): grava a `Idempotency-Key`, cria o pedido e a `reserva` (`ATIVA`, com `expira_em`) e, por último, baixa o estoque. Não grava pagamento. Se o `UPDATE` afetar 0 linhas, a T1 volta e a resposta é `409`.
2. Chama o parceiro fora de transação (Resilience4j).
3. T2, quando o parceiro responde: grava a linha de pagamento. Resultado claro nasce `APROVADO` ou `RECUSADO`. `PENDENTE` só se a chamada já saiu e o resultado é ambíguo (timeout ou 500); o pedido fica em `PAGAMENTO_EM_VERIFICACAO`. 503 sem chamada não cria linha. Se `RECUSADO` ou `EXPIRADO`, na mesma transação libera a reserva só se ainda estiver `ATIVA`. Se `APROVADO`, marca `CONFIRMADA`, com a mesma guarda.

Máximo de compras/s por produto ≈ 1000 ms ÷ X, com X = tempo em ms que a T1 segura o lock. Hipótese, não medida.

| Hipótese para X | Conta | Máximo por produto |
|---|---|---|
| Caso bom: X ≈ 2 ms | 1000 ÷ 2 | ≈ 500 compras/s |
| Referência: X ≈ 4 ms | 1000 ÷ 4 | ≈ 250 compras/s |
| `UPDATE` no começo da T1, X ≈ 10 ms | 1000 ÷ 10 | ≈ 100 compras/s |

Com X = 4 ms, 5.000 pedidos do mesmo produto: a fila anda a 250/s e o último esperaria 20 s. Mais conexões não aceleram. `lock_timeout = '2s'` deixa só as primeiras ~500 posições; o resto espera 2 s e recebe `503`, cada um segurando uma conexão. Um pool de 50 conexões fica inteiro na fila de um produto.

Bulkhead por produto (semáforo, uma instância por `sku`, em volta da T1): 8 vagas por réplica, `max-wait-duration` de 50 ms. Quem não pega vaga recebe `503` + `Retry-After` sem abrir conexão e sem gravar a chave. O semáforo nasce na primeira requisição do SKU e sai depois de 15 min sem chamada. Teto de 1.000 semáforos; o que passaria entra no semáforo compartilhado `outros`, com as mesmas 8 vagas. Réplica que sobe começa vazia. Os 15 min e o teto de 1.000 são folga, a calibrar no teste de carga.

4 réplicas × 8 vagas = 32 na fila → espera máxima ≈ 32 × 4 ms ≈ 130 ms. O produto quente usa no máximo 8 conexões por réplica. Quando o estoque acaba, o `UPDATE` responde `409` na hora (100 unidades a 250/s acabam em ~0,4 s).

<a id="riscos"></a>
## Riscos

| Risco | Onde | Mitigação |
|---|---|---|
| Excesso de `OptimisticLockException` | Pagamento | Conflito é raro. Quem perde relê e respeita o estado; nada de retry cego. |
| Fila no lock do produto quente | Estoque | `UPDATE` é o último passo da T1. `SET LOCAL lock_timeout = '2s'`. |
| Pool esgotado (`ConnectionTimeoutException`) | Todo o banco | `connectionTimeout` curto (ex.: 2 s) e Bulkhead por produto antes do banco. O tamanho do pool sai do teste de carga. |
| Deadlock | Carrinho com vários produtos | Reservar sempre em ordem crescente de `sku`. O Postgres aborta uma com `40P01` (`deadlock_timeout` de 1 s). O abortado repete a T1 uma vez. |
| Unidade presa | Estoque | A reserva tem `expira_em`; ao marcar `EXPIRADO`, a reconciliação libera a reserva. |

Descartado: dividir o estoque em várias linhas ("baldes"). Só entra se o teste de carga mostrar que o `UPDATE` atômico não aguenta.

<a id="diagnostico"></a>
## Diagnóstico

1. Confirmar o sintoma: `hikaricp.connections.active`, `.pending`, `.usage`. O pool está cheio porque cada conexão fica presa, não porque faltam conexões.
2. `pg_stat_activity`: `now() - query_start` e `wait_event`. `Lock` é concorrência; I/O ou CPU é a consulta.
3. `pg_stat_statements` por `total_exec_time` e por `mean_exec_time`.
4. `EXPLAIN (ANALYZE, BUFFERS)` com valores reais. Procurar `Seq Scan`, "Rows Removed by Filter" e `Buffers`. `EXPLAIN ANALYZE` executa; para `UPDATE`/`DELETE`, só dentro de `BEGIN; ... ROLLBACK;`.
5. Se o planejador estima 10 linhas e vêm 50 mil, `ANALYZE pedidos` antes de culpar o índice.
6. Criar o índice num ambiente com volume de produção e repetir o plano e a carga.

O laboratório (`desafio/README.md`) mediu outra consulta: `SELECT * FROM olist_orders WHERE customer_id = ?`, sem `order_status`. Antes: `Seq Scan`, 1.539 buffers, 6,5 ms, cerca de 100 mil linhas removidas. Depois de `CREATE INDEX idx_orders_customer ON olist_orders (customer_id)`: `Bitmap Heap Scan` com `Bitmap Index Scan`, 11 buffers, 0,06 ms. Não é `Index Scan` puro e não é a consulta do incidente.

A consulta do incidente é `WHERE customer_id = ? AND order_status = 'processing'`. O índice é `(customer_id, order_status)`. O bloco abaixo é ilustração de formato, não medição. PostgreSQL 17. Os números fecham entre si: 5 linhas devolvidas + 199.995 removidas pelo filtro = 200.000. Rode o EXPLAIN no seu banco.

Antes, ilustração (`Seq Scan`, `loops=1`):

```text
Seq Scan on pedidos  (actual time=0.210..7.500 rows=5 loops=1)
  Filter: ((customer_id = '…9f02'::uuid) AND (order_status = 'processing'::text))
  Rows Removed by Filter: 199995
  Buffers: shared hit=2062
Planning Time: 0.080 ms
Execution Time: 7.500 ms
```

Depois de `CREATE INDEX idx_pedidos_cliente_status ON pedidos (customer_id, order_status)`, ilustração (`Index Scan`, `loops=1`):

```text
Index Scan using idx_pedidos_cliente_status on pedidos  (actual time=0.018..0.036 rows=5 loops=1)
  Index Cond: ((customer_id = '…9f02'::uuid) AND (order_status = 'processing'::text))
  Buffers: shared hit=8
Planning Time: 0.120 ms
Execution Time: 0.036 ms
```

<a id="indices"></a>
## Índices

Não aprovar um índice por coluna.

- A consulta é `WHERE customer_id = ? AND order_status = 'processing'`. Dois índices separados viram filtro ou `BitmapAnd`. O composto é `(customer_id, order_status)`. Se a tela ordena por data, `(customer_id, order_status, criado_em DESC)` elimina o `Sort`.
- `order_status` sozinho, com 90% `delivered`, vira `Seq Scan`. Para o worker, índice parcial: `CREATE INDEX ... ON pedidos (criado_em) WHERE order_status = 'processing'`. Reconciliação da Aula 2: `ON pagamento (criado_em) WHERE status = 'PENDENTE'`.
- Cada índice é atualizado em todo `INSERT` e em `UPDATE` de coluna indexada. Indexar `order_status` tira o HOT update.
- No laboratório, os índices de `olist_orders` somavam 13 MB contra 12 MB da tabela.
- O PR precisa de `EXPLAIN` antes e depois e de `CREATE INDEX CONCURRENTLY`. Depois, `pg_stat_user_indexes` com `idx_scan = 0` mostra índice sem uso.

<a id="diagrama"></a>
## Diagrama

Caminho síncrono, antes do Pix com QR. `AGUARDANDO_CAPTURA` e a exceção `EXPIRADO` → `APROVADO` estão na regra única.

```mermaid
sequenceDiagram
  participant C as Cliente
  participant API as Checkout API
  participant DB as PostgreSQL (primário)
  participant P as Parceiro
  C->>API: POST /checkout (Idempotency-Key)
  API->>DB: T1: INSERT chave UNIQUE + pedido + reserva ATIVA; por último UPDATE estoque (disponivel >= qtd)
  alt 0 linhas no estoque
    DB-->>API: esgotado
    API-->>C: 409 (nada cobrado)
  else reservado
    DB-->>API: commit
    API->>P: cobrança (fora de transação, Resilience4j)
    P-->>API: aprovado / recusado / timeout ou 500
    API->>DB: T2: INSERT pagamento (APROVADO, RECUSADO ou, só no resultado ambíguo, PENDENTE)
    opt recusado ou expirado
      API->>DB: libera reserva WHERE status = ATIVA (mesma T2)
    end
    API-->>C: 201 / 402, ou 202 se o pagamento ficou PENDENTE
  end
  Note over DB: 503 sem chamada não cria pagamento. Sem linha e com cobrança no parceiro, a reconciliação insere a linha (como a Aula 2). Com linha PENDENTE, só fecha. Caminho síncrono, antes do Pix com QR
```

<a id="testes"></a>
## Testes

Testcontainers com PostgreSQL 17. H2 ou mock não mostram lock de linha, `CHECK` nem plano. Cada teste nasce vermelho.

| Cenário | O que o teste afirma |
|---|---|
| 200 threads compram o mesmo produto com 5 unidades | Exatamente 5 pedidos `201`, 195 respostas `409`, `disponivel = 0`, nunca negativo. |
| Duas transações fecham o mesmo `PENDENTE` | Uma vence; a outra recebe `ObjectOptimisticLockingFailureException`; a reserva foi liberada no máximo uma vez. |
| Pagamento recusado | Reserva `LIBERADA`, estoque devolvido; repetir não devolve de novo. |
| Recusado e depois outro cartão com a mesma chave | `422`; o mesmo cartão devolve o `402` guardado. |
| Bulkhead do produto cheio | `503` + `Retry-After` sem pegar conexão. |
| Carrinho A+B e B+A em paralelo | Nenhum deadlock; sem a ordenação, aparece `40P01`. |
| Lock preso por 5 s | A compra concorrente responde `503` em ~2 s (`lock_timeout`). |
| Consulta por cliente e status, 1 milhão de linhas | O plano usa o índice composto; não aparece `Seq Scan` em `pedidos`. |
| Migration do índice | `CONCURRENTLY` enquanto outra thread insere: os `INSERT` não bloqueiam. |
| Leitura do status logo após comprar | Vem do primário, nunca da réplica. |

O Testcontainers prova a regra, não a escala. O X da seção de estoque, as vagas do Bulkhead e o tamanho do pool saem de um teste de carga.
