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

---

## 1. Decisão de Armazenamento e Chave Primária (PK)

### 1.1 Motor de Banco de Dados e Paradigma de Persistência

- **A Escolha para o Checkout:** ( ) Relacional Puro (PostgreSQL) (X) Persistência Poliglota / Híbrida
- **Justificativa Arquitetural:** Avalie o Teorema CAP e o modelo de consistência (ACID vs BASE). Por que a sua escolha atende aos requisitos do Checkout sem comprometer a integridade financeira?
  > **O núcleo do Checkout é PostgreSQL, sem negociação.** Uma compra mexe em quatro coisas que precisam dar certo juntas ou não acontecer: a chave de idempotência, a reserva do estoque, o pedido e o pagamento. Isso é uma transação ACID: atomicidade (não existe reserva sem pedido), consistência (`CHECK (disponivel >= 0)` e `UNIQUE` valem sempre), isolamento (duas compras do mesmo produto não se atropelam) e durabilidade (o `201` só sai depois do commit).
  >
  > **CAP.** Numa partição de rede, para dinheiro e estoque escolhemos **C** (consistência): é melhor responder `503` do que vender a mesma unidade duas vezes ou mostrar "pago" sem pagamento (FALHA: na dúvida, nega). É a mesma regra da Aula 02, agora no banco.
  >
  > **Onde aceitamos BASE (consistência eventual):** o que não decide dinheiro nem estoque. A busca de produtos pode mostrar um preço com segundos de atraso, porque **o preço cobrado vem do catálogo no Postgres na hora da compra**, não do índice de busca nem do navegador (LEITURA). O pesado (indexar na busca, mandar para o analytics) sai do clique: grava no Postgres e segue (CLIQUE).
  >
  > **Fronteira de consistência:**
  >
  > | Dado | Onde mora | Consistência | Por quê |
  > |---|---|---|---|
  > | Pedido, pagamento, estoque, `idempotency_keys` | PostgreSQL (primário) | ACID, imediata | Dinheiro e unidade física. |
  > | Status do pedido para o cliente logo após comprar | PostgreSQL **primário** | Imediata | Ler da réplica logo após gravar faz o cliente pagar e "não ver" o pedido (LEITURA). |
  > | Histórico antigo de pedidos, relatórios | Réplica de leitura | Eventual (segundos) | Tira carga do primário; atraso aceitável. |
  > | Catálogo para busca, carrinho, sessão, telemetria | NoSQL (abaixo) | Eventual | Alta vazão; perder ou atrasar não cobra ninguém. |
  >
  > **O que foi descartado:** colocar pedidos num banco documental "para escalar". O gargalo do incidente é falta de índice e de controle de concorrência, não o modelo relacional. O mais simples que atende primeiro (CANO): índices, transações curtas, réplica de leitura. *Sharding* só quando a dor for real (CORTE).

- **Além do Modelo Documental (Ecossistema NoSQL):** A persistência poliglota vai muito além da dicotomia "Relacional vs MongoDB". Considerando as necessidades complementares de uma plataforma de e-commerce (como carrinhos efêmeros, busca textual de produtos, telemetria/logs, auditoria temporal ou análise de conexões/fraude), cite e comente pelo menos outros dois tipos/famílias de bancos NoSQL que poderiam coexistir na arquitetura, indicando para quais casos de uso específicos cada um seria a ferramenta ideal e por quê.
  > Cada família entra porque uma dor pede (DOR), nunca no caminho do dinheiro:
  >
  > | Família | Exemplo | Caso de uso | Por quê |
  > |---|---|---|---|
  > | **Chave-valor em memória** | Redis | Carrinho e sessão, com TTL | Leitura e escrita por chave em sub-milissegundo; o carrinho é efêmero e perder um não cobra ninguém. **Não** usamos Redis para a idempotência nem para o estado do Circuit Breaker (decisão da Aula 02: mais uma dependência que pode cair; a trava é o `UNIQUE` do Postgres). |
  > | **Busca textual** | OpenSearch / Elasticsearch | Busca de produtos por nome, filtros, relevância | Índice invertido faz o que `LIKE '%fone%'` não faz. É alimentado de forma assíncrona; o preço exibido é informativo, o cobrado vem do Postgres. |
  > | **Séries temporais** | TimescaleDB, Prometheus | Telemetria: p99, uso do pool, taxa de `OptimisticLockException` | Grava muito, lê por janela de tempo, descarta dados velhos. |
  > | **Grafo** | Neo4j | Antifraude: mesmo cartão, dispositivo ou endereço ligando contas | Consultas de "quem está ligado a quem" em vários saltos, que em SQL viram muitos `JOIN`. Fica fora do caminho síncrono no início. |
  >
  > Documental (MongoDB) também cabe para o catálogo com atributos variáveis por categoria, mas o Postgres com `jsonb` atende hoje; só sobe outro banco quando a dor aparecer.

### 1.2 Estratégia da Chave Primária

- **A Escolha:** ( ) Sequence / Identity ( ) UUIDv4 (X) UUIDv7 / TSID
- **Impacto no Armazenamento:** Por que essa estratégia foi escolhida considerando inserções concorrentes e o impacto de fragmentação na árvore B-Tree (page splits)?
  > **Por que não Sequence/Identity.** O auto-incremento depende de um contador central no banco: o ID só existe depois do `INSERT`, então não dá para gerar o ID do pedido antes (para mandar ao parceiro como referência) nem em vários bancos sem coordenação. No Hibernate, `GenerationType.IDENTITY` ainda **desliga o JDBC batch insert**, porque o Hibernate precisa executar cada `INSERT` na hora para saber o ID. (`SEQUENCE` com `allocationSize = 50` resolveria o batch, mas continua um contador central.)
  >
  > **Por que não UUIDv4.** O v4 é 100% aleatório. Cada `INSERT` cai num lugar qualquer da B-Tree da PK: páginas no meio do índice enchem e se dividem (*page splits*), o índice fica fragmentado e o cache sofre, porque a "página quente" é o índice inteiro. Medimos isso no laboratório (pasta `desafio/`): 300 mil linhas com PK aleatória (md5, equivalente ao v4) geraram um índice de **22 MB com 70% de densidade nas folhas** e levaram 1,3 s; com bigint sequencial, **6,6 MB, 90% de densidade**, 0,5 s. (Parte do tamanho vem do texto de 32 caracteres; um `uuid` nativo ocupa 16 bytes.)
  >
  > **Por que UUIDv7.** Os primeiros 48 bits são o horário em milissegundos, e o resto é aleatório. É gerado na aplicação, sem coordenação e sem colisão prática, mas os novos IDs ficam sempre "à direita" da B-Tree, como numa sequence: poucas divisões de página, índice compacto. Também dá para gerar o ID do pedido **antes** do `INSERT` e usá-lo como referência no parceiro.
  >
  > **Como implementar:** coluna do tipo `uuid` (16 bytes, nunca `varchar`). O PostgreSQL 17 **não** tem função `uuidv7()` nativa (ela chega no PostgreSQL 18), então o ID é gerado no Java, por exemplo com a biblioteca `uuid-creator` (`UuidCreator.getTimeOrderedEpoch()`), e atribuído na entidade antes do `persist`.
  >
  > **Cuidado:** o v7 revela o horário de criação. Para o pedido isso não é segredo, mas não usamos o ID como prova de posse: o serviço confere o dono, e pedido de outro cliente responde `404` (DONO).
  >
  > **A `Idempotency-Key` da Aula 02 não muda.** Ela é gerada pelo cliente (UUID qualquer) e é uma coluna `UNIQUE`, não a PK; o índice dela recebe inserções aleatórias, custo aceito porque a tabela é pequena (TTL de 24 h, linhas antigas apagadas).

### 1.3 Modelo de Dados (campos principais)

> **Material extra:** esta subseção não é pedida pelo template da Aula 03; foi mantida para mostrar onde moram as travas usadas nas seções 1.2 e 2.


- **Tabelas da compra:**
  > Só o que a compra precisa. IDs são `uuid` v7 gerados no Java (1.2); dinheiro é `numeric(12,2)`, nunca `float`; datas são `timestamptz`; status são `text` com `CHECK` na lista de valores (legível no `psql` e mapeado com `@Enumerated(EnumType.STRING)`).
  >
  > | Tabela | Campos principais | Constraints e regras |
  > |---|---|---|
  > | `produto` | `sku text`, `nome text`, `preco numeric(12,2)`, `atributos jsonb` | PK `sku`; `CHECK (preco > 0)`. É daqui que sai o preço **cobrado** (1.1). |
  > | `estoque` | `sku text`, `disponivel int` | PK e FK `sku` → `produto`; `CHECK (disponivel >= 0)`. **Sem `@Version`**: a trava é o `UPDATE` atômico (2.2). |
  > | `pedidos` | `id uuid`, `customer_id uuid`, `order_status text`, `total numeric(12,2)`, `criado_em timestamptz`, `version bigint` | PK `id`; `customer_id NOT NULL`; `CHECK` na lista de `order_status` (ex.: `processing`, `delivered`); `@Version` (baixa disputa, como 2.1). Índices na seção 3.2. |
  > | `pagamento` | `id uuid`, `order_id uuid`, `valor numeric(12,2)`, `status text`, `version bigint`, `criado_em timestamptz` | FK `order_id` → `pedidos` e **`UNIQUE (order_id)`: um pagamento por pedido**; `CHECK (status IN ('PENDENTE','APROVADO','RECUSADO','EXPIRADO'))`; `@Version` (2.1). |
  > | `reserva` | `id uuid`, `order_id uuid`, `sku text`, `qtd int`, `status text`, `expira_em timestamptz` | FKs para `pedidos` e `estoque`; `UNIQUE (order_id, sku)`; `CHECK (qtd > 0)`; `CHECK (status IN ('ATIVA','CONFIRMADA','LIBERADA'))`; `expira_em NOT NULL`. |
  > | `idempotency_keys` | `id uuid`, `chave uuid`, `hash_do_corpo text`, `status text`, `order_id uuid`, `resposta_status int`, `resposta jsonb`, `travado_ate timestamptz`, `expira_em timestamptz` | PK `id`; **`UNIQUE (chave)`** (1.2); `CHECK (status IN ('EM_PROCESSAMENTO','CONCLUIDA'))`; `resposta` nula até concluir; `expira_em` = criação + 24 h. |
  >
  > **Como a chave única da compra funciona:** duas travas no banco, nenhuma na memória. (1) `UNIQUE (chave)` em `idempotency_keys`: de duas requisições com a mesma chave, só um `INSERT` vence (Aula 02). (2) `UNIQUE (order_id)` em `pagamento`: mesmo depois que a chave expira e é apagada, o pedido não ganha uma segunda cobrança.
  >
  > **Como o estado da reserva funciona:** nasce `ATIVA` na T1, com `expira_em`. Só sai de `ATIVA`, e uma vez só: `APROVADO` → `CONFIRMADA`; `RECUSADO`, `EXPIRADO` ou prazo vencido → `LIBERADA`, devolvendo a unidade. Toda transição é `UPDATE ... WHERE status = 'ATIVA'`; 0 linhas afetadas = alguém já fechou, não faz nada (ESTADO).
  >
  > **Escolha de negócio: cartão recusado encerra o pedido.** A `Idempotency-Key` é a chave da compra (da intenção, Aula 02) e o pagamento é único por pedido. Então um `402` fica guardado como resposta final: repetir com a mesma chave devolve o mesmo `402`, e mandar outro cartão com a mesma chave muda o corpo e dá `422`. Para tentar outro cartão, o cliente **começa um checkout novo** (chave nova, pedido novo) e disputa o estoque de novo, porque a reserva do pedido recusado já foi liberada. Aceitamos isso pela simplicidade: um pedido, uma cobrança, sem estado de "segunda tentativa". **Descartado por agora:** várias tentativas no mesmo pedido (tabela de tentativas com `UNIQUE` parcial em `order_id WHERE status IN ('PENDENTE','APROVADO')`, segurando a reserva). Só entra se o negócio pedir, por exemplo se medirmos venda perdida depois de recusa (DOR).

---

## 2. A Dualidade da Concorrência: Locks e Mitigações

### 2.1 Mudança de Status do Pagamento (Baixa contenção por registro)

- **Abordagem de Lock:** (X) Lock Otimista (`@Version`) ou ( ) Lock Pessimista (`SELECT FOR UPDATE`)?
- **Justificativa:** Por que essa abordagem é a mais adequada para este cenário específico?
  > Um pagamento quase nunca é disputado: no caso comum, só a própria requisição o fecha. Prender a linha com `FOR UPDATE` em toda mudança seria pagar fila por um conflito raro. Com `@Version`, o Hibernate grava `UPDATE pagamento SET status = ?, version = version + 1 WHERE id = ? AND version = ?`. Se outra transação mudou a linha antes, 0 linhas são afetadas e o Hibernate lança `OptimisticLockException` (no Spring, `ObjectOptimisticLockingFailureException`).
  >
  > **O conflito raro existe, e é o da Aula 02:** resposta tardia do parceiro e job de reconciliação fechando o mesmo `PENDENTE`. Quem perder a corrida **não tenta de novo às cegas**: relê o pagamento; se já saiu de `PENDENTE`, não faz nada (evento atrasado só muda o que o estado atual ainda permite: ESTADO). A transição também é guardada pela regra no próprio `UPDATE` (`... AND status = 'PENDENTE'`), então mesmo um caminho sem JPA não reabre um pagamento fechado.
  >
  > ```java
  > @Entity
  > class Pagamento {
  >   @Id UUID id;                         // UUIDv7 gerado na aplicação
  >   @Version long version;
  >   @Enumerated(EnumType.STRING) StatusPagamento status;
  >
  >   void fechar(StatusPagamento novo) {
  >     if (status != StatusPagamento.PENDENTE) return;   // já fechado: ignora
  >     status = novo;
  >   }
  > }
  > ```

### 2.2 Controle de Estoque Concorrente (Alta contenção no mesmo registro)

- **Abordagem de Lock:** ( ) Lock Otimista (`@Version`) ou (X) Lock Pessimista (`SELECT FOR UPDATE`)?
- **Justificativa:** Centenas de usuários disputando os últimos itens na Black Friday. Como você garante que o saldo nunca fique negativo sem derrubar o banco?
  > Com centenas de clientes no mesmo produto, o otimista vira uma tempestade de falhas: quase todos leem a mesma versão, um vence e o resto recebe `OptimisticLockException`, tenta de novo e perde de novo. Gasta CPU e conexão e frustra o cliente que ainda tinha unidade para comprar.
  >
  > **Implementação: um `UPDATE` atômico e condicional.** Ele trava a linha do produto, e no Postgres o lock de linha só é solto no **commit** da transação. Por isso esse `UPDATE` é o **último comando da T1**, logo antes do commit:
  >
  > ```sql
  > UPDATE estoque
  >    SET disponivel = disponivel - :qtd
  >  WHERE sku = :sku AND disponivel >= :qtd;
  > -- 1 linha afetada: reservou. 0 linhas: esgotado -> 409, nunca "OK".
  > ```
  >
  > É lock pessimista (o Postgres trava a linha e as outras esperam), mas sem o "lê, calcula no Java, grava": a regra mora numa frase só, e o `CHECK (disponivel >= 0)` na tabela é a segunda trava. O `SELECT ... FOR UPDATE` explícito (`@Lock(LockModeType.PESSIMISTIC_WRITE)` no repositório) fica para quando a regra não cabe num `UPDATE`.
  >
  > **Regra de ouro (herdada da Aula 02): nunca segurar lock nem transação durante a chamada ao parceiro.** O fluxo tem duas transações curtas:
  > 1. **T1** (milissegundos): grava a `Idempotency-Key`, cria pedido, pagamento `PENDENTE` e `reserva` (`ATIVA`, com `expira_em`) e, **por último**, baixa o estoque (o `UPDATE` acima). Commit. Se o `UPDATE` afetar 0 linhas, a T1 inteira volta (`ROLLBACK`) e a resposta é `409`.
  > 2. Chama o parceiro **fora de transação** (Resilience4j).
  > 3. **T2**: fecha o pagamento (2.1). Se `RECUSADO` ou `EXPIRADO`, na mesma transação, libera a reserva e devolve a unidade, só se a reserva ainda estiver `ATIVA` (`UPDATE reserva SET status='LIBERADA' WHERE id=? AND status='ATIVA'`). Isso impede devolver duas vezes (ESTADO). Se `APROVADO`, marca a reserva `CONFIRMADA`, com a mesma guarda `WHERE status='ATIVA'`.
  >
  > **Quanto a linha quente aguenta (estimativa, não medida).** A linha do produto atende **uma compra por vez**: enquanto uma T1 segura o lock, as outras esperam na fila. A conta é:
  >
  > **máximo de compras/s por produto ≈ 1000 ms ÷ X**, onde X é o tempo (em ms) que a T1 segura o lock, do `UPDATE` do estoque até o commit.
  >
  > | Hipótese para X (a validar no teste de carga) | Conta | Máximo por produto |
  > |---|---|---|
  > | Caso bom: banco perto da aplicação, disco rápido, X ≈ 2 ms | 1000 ÷ 2 | ≈ 500 compras/s |
  > | **Referência:** `UPDATE` + `COMMIT` (duas idas ao banco + gravação do WAL em disco), X ≈ 4 ms | 1000 ÷ 4 | **≈ 250 compras/s** |
  > | `UPDATE` no começo da T1 (o lock segura também os `INSERT`), X ≈ 10 ms | 1000 ÷ 10 | ≈ 100 compras/s |
  >
  > **Os primeiros segundos (exemplo com X = 4 ms):** 5.000 pedidos do mesmo produto chegam juntos.
  > - A fila anda a 250/s: o último esperaria 5.000 ÷ 250 = **20 s**. Mais conexões **não** aceleram nada (a linha é uma só); só aumentam a espera.
  > - O `lock_timeout = '2s'` corta antes: só as primeiras ~250 × 2 = **500** posições da fila pegam o lock; o resto espera 2 s e recebe `503`. Mas cada um ficou **2 s segurando uma conexão**: com um pool de, por exemplo, 50 conexões, a fila de **um** produto ocupa o pool inteiro e trava a compra de **todos** os outros.
  > - **Por isso há um limite antes do banco:** um **Bulkhead por produto** (semáforo do Resilience4j, o mesmo padrão da Aula 02, uma instância por `sku`, envolvendo a T1 inteira), com poucas vagas (ex.: 8 por réplica) e `max-wait-duration` curto (ex.: 50 ms). Quem não pega vaga recebe `503` + `Retry-After` **sem abrir conexão** e sem gravar a chave; o cliente repete com a mesma `Idempotency-Key`.
  > - **Espera com o Bulkhead:** 4 réplicas × 8 vagas = 32 na fila da linha → espera máxima ≈ 32 × 4 ms ≈ **130 ms**, bem abaixo dos 2 s. O produto quente usa no máximo 8 conexões por réplica; o resto do pool fica para os outros produtos. O `lock_timeout` vira rede de segurança, não o controle do dia a dia.
  > - **Quando o estoque acaba**, o `UPDATE` não acha mais `disponivel >= :qtd` e responde `409` na hora: a fila some sozinha (ex.: 100 unidades a 250/s acabam em ~0,4 s).
  >
  > Todos esses números são **hipóteses de ordem de grandeza**, não medidas. X, as vagas do Bulkhead e o tamanho do pool saem do teste de carga (seção 5).

### 2.3 Riscos e Mitigações

- **Análise Crítica:** Quais os riscos operacionais das abordagens de lock que você escolheu (ex: excesso de `OptimisticLockException` frustrando o cliente vs `ConnectionTimeoutException` e *deadlocks* enfileirando threads no Lock Pessimista)? Como sua arquitetura mitiga esses riscos?
  > | Risco | Onde | Mitigação |
  > |---|---|---|
  > | Excesso de `OptimisticLockException` | Pagamento (2.1) | Conflito é raro por desenho. Quem perde relê e respeita o estado; nada de retry cego. Métrica de taxa de conflitos com alerta: subiu, alguém está disputando o que não devia. |
  > | Fila de espera no lock do produto quente | Estoque (2.2) | O lock da linha dura do `UPDATE` até o **commit** da T1; por isso o `UPDATE` é o último passo da T1, que é curta e sem chamada externa (2.2). `SET LOCAL lock_timeout = '2s'` na transação: quem não consegue o lock em 2 s recebe erro e responde `503` com `Retry-After`, em vez de prender conexão. Conta da vazão e da espera em 2.2 (estimativa). |
  > | Pool de conexões com o banco esgotado (`ConnectionTimeoutException`) | Todo o banco | Com threads virtuais, threads são baratas; o limite real é o pool (Aula 02). `connectionTimeout` curto (ex.: 2 s) para falhar rápido com `503`; um **Bulkhead por produto** (o padrão da Aula 02, que lá protege o parceiro, agora antes do banco) limita quantas requisições do mesmo produto chegam ao banco ao mesmo tempo (2.2). O tamanho do pool sai do teste de carga, não de chute. |
  > | *Deadlock* | Carrinho com vários produtos | Duas compras travando produtos em ordem diferente (A→B e B→A) se esperam para sempre. O Postgres detecta (após `deadlock_timeout`, 1 s por padrão) e aborta uma com erro `40P01`. Mitigação: **reservar os produtos sempre em ordem crescente de `sku`**; o abortado repete a T1 uma vez, sem efeito colateral, porque nada foi commitado. |
  > | Unidade presa por pagamento que não fecha | Estoque | A reserva tem `expira_em`; a reconciliação da Aula 02, ao marcar `EXPIRADO`, libera a reserva (2.2, passo 3). |
  >
  > **Descartado:** dividir o estoque do produto em várias linhas ("baldes") para reduzir disputa. Resolve contenção extrema, mas complica a contagem; só entra se o teste de carga mostrar que o `UPDATE` atômico não aguenta (DOR).

---

## 3. Troubleshooting do Incidente e Gestão de Índices

### 3.1 Investigação de Incidente em Produção

- **Cenário:** Em testes de carga para a Black Friday, o tempo de resposta p99 do Checkout subiu repentinamente para 4.8s e o pool de conexões (HikariCP) saturou. Os logs apontam que a consulta de pedidos mais frequente (`WHERE customer_id = ? AND order_status = 'processing'`) está demorando segundos para responder.
- **Plano de Diagnóstico:** Qual é o seu processo técnico passo a passo para investigar a causa raiz dessa lentidão diretamente no banco de dados? Quais comandos, métricas ou planos você analisaria para comprovar a hipótese antes de propor qualquer mudança?
  > Primeiro provar, depois mexer. Nenhum índice entra sem plano antes e depois.
  >
  > 1. **Confirmar o sintoma na aplicação.** Métricas do pool de conexões com o banco (`hikaricp.connections.active`, `.pending`, `.usage`): o pool está cheio porque cada conexão fica presa muito tempo, não porque faltam conexões. Aumentar o pool só jogaria mais carga num banco a 100% de CPU.
  > 2. **Ver o que o banco está fazendo agora.** `pg_stat_activity`: consultas ativas, há quanto tempo (`now() - query_start`) e o `wait_event` (I/O, lock). Se o `wait_event_type` é `Lock`, o problema é concorrência (seção 2); se é I/O/CPU, é a consulta.
  > 3. **Achar a consulta que mais pesa.** `pg_stat_statements` ordenado por `total_exec_time` (o que mais consome no total) e por `mean_exec_time` (a mais lenta por chamada). A consulta de pedidos por cliente e status aparece no topo pelas duas.
  > 4. **Ler o plano real.** `EXPLAIN (ANALYZE, BUFFERS)` com valores reais. Procuramos `Seq Scan` em `pedidos`, "Rows Removed by Filter" enorme comparado às linhas devolvidas, e muitos `Buffers` lidos. Colamos a saída no explain.dalibo.com para ver o nó mais caro. **Atenção:** `EXPLAIN ANALYZE` **executa** o comando; para `UPDATE`/`DELETE`, só dentro de `BEGIN; ... ROLLBACK;`.
  > 5. **Conferir as estatísticas.** Se o planejador estima 10 linhas e vêm 50 mil, rodar `ANALYZE pedidos` antes de culpar o índice.
  > 6. **Hipótese e prova.** Criar o índice num ambiente com volume de produção, repetir o mesmo `EXPLAIN` e a mesma carga, e comparar p99, pool e buffers. No laboratório, o filtro por cliente caiu de 6,5 ms (`Seq Scan`, 1.539 buffers) para 0,06 ms (`Index Scan`, 11 buffers).

### 3.2 Decisão de Engenharia: A Abordagem de Índices

- **Dilema no Pull Request:** Para solucionar a lentidão, um desenvolvedor da equipe abriu um PR criando índices individuais para todas as colunas da tabela de pedidos que aparecem em filtros de consulta.
- **Seu Posicionamento:** Como responsável técnico, você aprovaria esse PR? Qual é a sua avaliação crítica sobre essa estratégia e quais impactos colaterais sistêmicos devem ser ponderados antes de aprovar novos índices?
  > **Não aprovo o PR.** Índice não é de graça e um por coluna nem resolve a consulta.
  >
  > - **Não resolve:** a consulta é `WHERE customer_id = ? AND order_status = 'processing'`. Dois índices separados obrigam o banco a escolher um e filtrar o resto, ou a combinar os dois (*BitmapAnd*). O certo é **um índice composto** na ordem do uso: `(customer_id, order_status)`, igualdade mais seletiva primeiro. Se a tela ordena por data, `(customer_id, order_status, criado_em DESC)` também elimina o `Sort`.
  > - **Índice em coluna pouco seletiva é inútil:** em `order_status` sozinho, com 90% `delivered`, o banco ignora o índice e faz `Seq Scan` (vimos no laboratório). Para o worker que só busca `processing`, o certo é um **índice parcial**: `CREATE INDEX ... ON pedidos (criado_em) WHERE order_status = 'processing'`, pequeno porque cobre só as linhas raras. O mesmo vale para a reconciliação da Aula 02: `ON pagamento (criado_em) WHERE status = 'PENDENTE'`.
  > - **Custo na escrita:** cada índice é atualizado em todo `INSERT` e em `UPDATE` de coluna indexada. Na Black Friday, o checkout é escrita pura. Mais índices = mais I/O por pedido e menos *HOT updates* (o Postgres só faz o update "no lugar" quando nenhuma coluna indexada muda: indexar `order_status` faz toda mudança de status atualizar índices).
  > - **Custo em disco e memória:** no laboratório, os índices de `olist_orders` já somavam **13 MB contra 12 MB da tabela**. Índice que não cabe no cache disputa memória com os dados quentes.
  > - **Faltou o que importa:** o PR não traz `EXPLAIN` antes e depois nem medida de escrita. Pedido de mudança: só os índices que a consulta real usa, com o plano anexado (DECISÃO), e criados com **`CREATE INDEX CONCURRENTLY`** (ver pergunta de risco 3). Depois, `pg_stat_user_indexes` (`idx_scan = 0`) mostra índice que ninguém usa, candidato a remoção.

---

## 4. Os Edge Cases (Engenharia do Caos)

> **Material extra:** esta seção não é pedida pelo template da Aula 03; foi mantida como estudo e para reforçar a robustez do desenho. As perguntas detalhadas estão em `perguntas-de-risco.md`.

1. **E se...** a resposta atrasada do parceiro e a reconciliação fecharem o mesmo `PENDENTE` ao mesmo tempo, com resultados diferentes?
   - **Resposta do Sistema:** os dois carregam o pagamento com `version = 3`. O primeiro commit grava `version = 4`; o `UPDATE ... WHERE version = 3` do segundo afeta 0 linhas e o Hibernate lança `OptimisticLockException`. O perdedor relê: o status já não é `PENDENTE`, então não faz nada e só registra o evento. O cliente nunca vê o pagamento "mudar de ideia". Se a reconciliação perdeu para um `APROVADO`, o estoque **não** é devolvido, porque a devolução está na mesma transação que foi abortada.
2. **E se...** o pagamento for recusado ou expirar depois que a última unidade já foi reservada?
   - **Resposta do Sistema:** a T2 fecha o pagamento como `RECUSADO`/`EXPIRADO` e, **na mesma transação**, faz `UPDATE reserva SET status = 'LIBERADA' WHERE id = ? AND status = 'ATIVA'` e devolve a unidade só se essa linha mudou. Se a aplicação cair antes, a reserva vence (`expira_em`) e a reconciliação faz o mesmo. Liberar duas vezes é impossível: a segunda tentativa encontra `LIBERADA` e afeta 0 linhas.
3. **E se...** a migration do índice novo travar as gravações durante o pico?
   - **Resposta do Sistema:** `CREATE INDEX` comum pega um lock que bloqueia escritas na tabela. Usamos `CREATE INDEX CONCURRENTLY`, que não bloqueia `INSERT`/`UPDATE` (é mais lento e varre a tabela duas vezes). Ele não roda dentro de transação, então no Flyway a migration fica marcada como não transacional (arquivo de configuração ao lado do script, ex.: `V7__indice_pedidos.sql.conf` com `executeInTransaction=false`). Se falhar no meio, sobra um índice `INVALID`: o deploy confere `pg_index.indisvalid` e, se preciso, faz `DROP INDEX CONCURRENTLY` e cria de novo. E a migration roda **antes** da janela da Black Friday, nunca no pico.

```mermaid
sequenceDiagram
  participant C as Cliente
  participant API as Checkout API
  participant DB as PostgreSQL (primário)
  participant P as Parceiro
  C->>API: POST /checkout (Idempotency-Key)
  API->>DB: T1: INSERT chave UNIQUE + pedido + pagamento PENDENTE + reserva ATIVA; por último UPDATE estoque (disponivel >= qtd)
  alt 0 linhas no estoque
    DB-->>API: esgotado
    API-->>C: 409 (nada cobrado)
  else reservado
    DB-->>API: commit
    API->>P: cobrança (fora de transação, Resilience4j)
    P-->>API: aprovado / recusado / timeout
    API->>DB: T2: UPDATE pagamento ... WHERE version = ? (e status = PENDENTE)
    opt recusado
      API->>DB: libera reserva WHERE status = ATIVA (mesma T2)
    end
    API-->>C: 201 / 402 / 202 PENDENTE
  end
  Note over DB: Reconciliação fecha PENDENTE vencido e libera a reserva com a mesma regra
```

---

## 5. Testando a Persistência

> **Material extra:** esta seção não é pedida pelo template da Aula 03; foi mantida como estudo e para reforçar a robustez do desenho.

**Ferramenta:** Testcontainers com PostgreSQL 17 de verdade. H2 ou mock do repositório não mostram lock de linha, `FOR UPDATE`, `CHECK` nem plano de execução: o mock devolve o que você mandou (PIRÂMIDE). Cada teste **nasce vermelho**: escrito antes da regra, tem de falhar sem ela (TESTE).

| Cenário | O que o teste afirma |
|---|---|
| 200 threads compram o mesmo produto com 5 unidades | Exatamente 5 pedidos `201`, 195 respostas `409`, `disponivel = 0`, nunca negativo. Sem o `WHERE disponivel >= :qtd`, o teste fica vermelho. |
| Duas transações fecham o mesmo `PENDENTE` (`CountDownLatch` para sincronizar) | Uma vence; a outra recebe `ObjectOptimisticLockingFailureException`; o status final é o do vencedor e a reserva foi liberada no máximo uma vez. |
| Pagamento recusado | Reserva `LIBERADA`, estoque devolvido; repetir o fechamento não devolve de novo. |
| Recusado e depois outro cartão com a mesma chave | `422`, nenhuma chamada nova ao parceiro; o mesmo cartão com a mesma chave devolve o `402` guardado. |
| Bulkhead do produto cheio | `503` + `Retry-After` sem pegar conexão do pool (métrica de conexões ativas não sobe). |
| Carrinho A+B e B+A em paralelo | Nenhum *deadlock* (ordenação por `sku`); com a ordenação removida, aparece `40P01`. |
| Lock preso por 5 s numa conexão de teste | A compra concorrente responde `503` em ~2 s (`lock_timeout`), sem prender a conexão. |
| Consulta de pedidos por cliente e status, com 1 milhão de linhas | O plano (`EXPLAIN (FORMAT JSON)`) usa o índice composto; não aparece `Seq Scan` em `pedidos`. |
| Migration do índice | Roda com `CONCURRENTLY` enquanto outra thread insere pedidos: os `INSERT` não ficam bloqueados. |
| Leitura do status logo após comprar | Vem do primário (rota de escrita), nunca da réplica. |

**Limite:** o Testcontainers prova a regra, não a escala. O p99, o tempo real do lock (o X da seção 2.2), as vagas do Bulkhead e o tamanho do pool saem de um teste de carga (ex.: Gatling ou k6) num banco com volume de produção, medindo o pool de conexões com o banco, `pg_stat_statements` e p99 antes e depois de cada mudança.
