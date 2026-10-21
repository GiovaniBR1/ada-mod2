# Template de System Design: Aula 02 - APIs Resilientes

> **Instruções:** Faça uma cópia deste arquivo e preencha as seções abaixo com as conclusões da sua pesquisa e os debates. O foco é a integração da API de Checkout com o Serviço de Pagamento Externo para suportar 10.000 usuários simultâneos.

## 1. Escolha de Paradigma da API

- **A Escolha:** (x) Spring MVC com Virtual Threads ou ( ) Spring WebFlux (Reativo)?
- **Justificativa:** Considerando a capacidade de suportar 10k acessos concorrentes, por que você fez essa escolha técnica?
  > **Spring MVC com threads virtuais** (`spring.threads.virtual.enabled=true`, Spring Boot 3.2+, Java 21).
  >
  > - **A carga é de espera, não de CPU.** Cada checkout passa a maior parte do tempo esperando o parceiro responder. Com threads virtuais, uma requisição bloqueada esperando I/O solta a thread real do sistema operacional; 10 mil esperas simultâneas custam memória, não 10 mil threads de SO.
  > - **Código imperativo, mais simples para o time (CANO).** Lê de cima para baixo, `try/catch` normal, stack trace legível, JDBC e Resilience4j sem adaptação. O time vem de Python/Go e está começando em Java; não pagamos a curva do `Mono`/`Flux` sem precisar.
  > - **Descartado: WebFlux.** Ganharia se tivéssemos streaming (SSE, WebSocket) ou precisássemos de backpressure de ponta a ponta, com driver reativo até o banco (R2DBC). Não é o caso: é um POST que espera uma resposta. Os números de throughput do material ("30-40%", "~1000 threads") não têm cenário; não usei como argumento, só um teste de carga nosso decidiria (DECISÃO).
  > - **O gargalo muda de lugar.** Com threads "de graça", quem limita são o **pool de conexões do banco** (o HikariCP vem com 10 por padrão) e a **cota do parceiro**. Por isso: (1) nunca segurar conexão ou transação do banco aberta durante a chamada ao parceiro; (2) um **Bulkhead** limita quantas chamadas simultâneas vão ao parceiro (seção 2).
  > - **Cuidado conhecido:** no Java 21, `synchronized` em volta de I/O prende a thread virtual na thread real (*pinning*). Usar `ReentrantLock` nesses pontos (o JDK 24 resolveu isso, JEP 491).

## 2. Desenho de Resiliência

Dependemos de uma API terceira instável. Quais recursos do padrão de resiliência você vai utilizar?

- **Proteção contra Falhas e Lentidão:**
  Quais padrões (Circuit Breaker, TimeLimiter, RateLimiter, Bulkhead, Retry) você implementaria na chamada para o Parceiro de Pagamentos? Por que?
  > Cada padrão entra porque uma dor pede (DOR). Ordem padrão das anotações do Resilience4j: `Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( chamada ) ) ) ) )`.
  >
  > | Padrão | Usa? | Por quê |
  > |---|---|---|
  > | **Timeout** (no cliente HTTP) | Sim | Sem limite, um parceiro lento segura a requisição para sempre. `connectTimeout` e timeout de resposta explícitos no cliente HTTP (ex.: 1 s e 2 s, valores iniciais). |
  > | **TimeLimiter** | Não, no caminho síncrono | A anotação só funciona com `CompletableFuture`/tipos reativos, e cancelar o futuro **não interrompe** a chamada bloqueante que roda por baixo. Com MVC + cliente bloqueante, quem realmente corta a espera e libera a thread é o timeout do cliente HTTP. Descartado para não dar falsa sensação de proteção. |
  > | **CircuitBreaker** | Sim | Parceiro doente: paramos de chamar por um tempo e respondemos rápido, em vez de empilhar 10 mil esperas de 2 s. Conta como falha: 5xx, timeout e erro de conexão. **Não** conta: recusa do cartão (402), que é resposta de negócio, nem a rejeição do nosso bulkhead (`BulkheadFullException`) nem a do rate limit (`RequestNotPermitted`). Essas duas saem antes da chamada: o parceiro nem foi chamado. Se o disjuntor as contasse, um pico nosso abriria o circuito e passaríamos a recusar chamadas que o parceiro ainda atenderia. |
  > | **Retry** | Sim, restrito | Só quando é seguro: erro de conexão **antes de enviar** (`ConnectException`) e 503/429 explícitos do parceiro, sempre com a **mesma chave de idempotência** enviada ao parceiro. Máximo 3 tentativas, backoff exponencial com jitter. **Nunca** repete timeout de leitura nem 500 (ambíguos: o parceiro pode ter cobrado), nem 4xx. |
  > | **Bulkhead** (semáforo) | Sim | Limita chamadas simultâneas ao parceiro (ex.: 200). Sem ele, 10 mil threads virtuais baratas viram 10 mil chamadas ao mesmo tempo num parceiro que já está mal. Semáforo, não pool de threads: com threads virtuais, não há pool a proteger. |
  > | **RateLimiter** | Sim, na saída | A cota do contrato é **um número para a frota inteira**, não por réplica. O Resilience4j conta só dentro do processo: se cada réplica receber a cota cheia, dez réplicas mandam dez vezes o contrato. **Decisão:** cada réplica fica com `cota / número de réplicas`, e o gateway (limite compartilhado) segura a cota cheia, para um erro na divisão não estourar o contrato. Exemplo: contrato de 500/s e 10 réplicas → 50/s em cada uma, e 500/s no gateway. Na entrada, limite por cliente com 429 é opcional, de preferência nesse mesmo gateway. |
  >
  > **Fallback (FALHA: na dúvida, nega; falha nunca vira "aprovado"):**
  > - Circuito aberto, bulkhead cheio ou rate limit estourado: **sabemos que não chamamos o parceiro**. Responde `503` com `Retry-After`; o cliente pode tentar de novo com a mesma chave.
  > - Timeout ou 500 depois de enviar: **não sabemos se cobrou**. O pagamento fica `PENDENTE` e a resposta é `202 { "status": "PENDENTE" }`. Um job de reconciliação consulta o parceiro pela chave do pedido e fecha em `APROVADO`, `RECUSADO` ou `EXPIRADO`. O fechamento só sai de `PENDENTE` (`UPDATE ... WHERE status = 'PENDENTE'`): evento atrasado não muda estado final (ESTADO).
  >
  > ```yaml
  > # Valores iniciais de exemplo, a calibrar com métrica real (p99 do parceiro, taxa de erro).
  > resilience4j:
  >   circuitbreaker.instances.parceiro:
  >     sliding-window-type: COUNT_BASED
  >     sliding-window-size: 50
  >     minimum-number-of-calls: 20
  >     failure-rate-threshold: 50          # % de falhas para abrir
  >     slow-call-duration-threshold: 1500ms
  >     slow-call-rate-threshold: 60        # % de chamadas lentas para abrir
  >     wait-duration-in-open-state: 30s
  >     permitted-number-of-calls-in-half-open-state: 5
  >     ignore-exceptions:
  >       - com.loja.checkout.CartaoRecusadoException
  >       - io.github.resilience4j.bulkhead.BulkheadFullException
  >       - io.github.resilience4j.ratelimiter.RequestNotPermitted
  >   retry.instances.parceiro:
  >     max-attempts: 3                     # inclui a primeira chamada
  >     wait-duration: 200ms
  >     enable-exponential-backoff: true
  >     enable-randomized-wait: true        # jitter
  >     retry-exceptions: [java.net.ConnectException, com.loja.checkout.ParceiroIndisponivelException]
  >   bulkhead.instances.parceiro:
  >     max-concurrent-calls: 200
  >     max-wait-duration: 50ms
  >   ratelimiter.instances.parceiro:
  >     limit-for-period: 50               # cota do contrato / réplicas (ex.: 500/s ÷ 10)
  >     limit-refresh-period: 1s
  >     timeout-duration: 0                # não espera vaga: 503 na hora
  > ```

- **Proteção contra Duplicidade:**
  Como a `Idempotency-Key` será utilizada na requisição do Checkout para evitar cobranças duplas? Qual o ciclo de vida dessa chave?
  > **A chave é da intenção, não da tentativa (IDEMPOTÊNCIA).** O cliente gera um UUID quando o usuário confirma o checkout e reenvia **a mesma** chave em toda tentativa. Sem o cabeçalho: `400`.
  >
  > **Ciclo de vida:**
  > 1. **Chega:** o servidor tenta `INSERT` em `idempotency_keys (chave, hash_do_corpo, status=EM_PROCESSAMENTO, resposta=null, expira_em)`. A chave é `UNIQUE`: a gravação atômica decide quem é o primeiro, sem "confere e depois grava".
  > 2. **Repetição:** mesma chave + mesmo hash e já concluída → devolve a resposta guardada, **com o status original** (201, 402 ou 202; se era 202, devolve o estado atual do pagamento). Mesma chave + corpo diferente → `422`. Ainda `EM_PROCESSAMENTO` → `409`, sem chamar o parceiro.
  > 3. **Parceiro:** a mesma chave (ou o `orderId` derivado dela) vai no `Idempotency-Key` do parceiro. Se algo reenviar, o parceiro também deduplica: duas travas.
  > 4. **Falha sem cobrança** (503 em que o parceiro não foi chamado: circuito aberto, bulkhead cheio ou rate limit): a chave é liberada e **não nasce linha em `pagamento`**. Falha ambígua (timeout ou 500 depois de enviar): aí sim grava o pagamento `PENDENTE` e a chave fica ligada a ele.
  > 5. **Expira:** TTL de **24 h** (escolha nossa: cobre os retries do cliente). Depois disso a linha pode ser apagada, mas o `order_id` único na tabela de pagamentos continua barrando a segunda cobrança do mesmo pedido.
  >
  > Um prazo (`travado_ate`) no `EM_PROCESSAMENTO` evita chave presa se a réplica cair no meio da chamada. Vencido o prazo, a reconciliação consulta o parceiro pela mesma chave. Se ele tiver a cobrança, grava o pagamento (final ou `PENDENTE`). Se não tiver, libera a chave. Não se grava `PENDENTE` antes de saber que a chamada saiu.
  >
  > **Material extra (não pedido pelo template):** campos principais das tabelas, para mostrar onde moram as duas travas. O modelo completo, com pedido, reserva e estoque, está na Aula 03.
  >
  > | Tabela | Campos | Constraints |
  > |---|---|---|
  > | `idempotency_keys` | `id uuid`, `chave uuid`, `hash_do_corpo text`, `status text`, `order_id uuid`, `resposta_status int`, `resposta jsonb`, `travado_ate timestamptz`, `expira_em timestamptz` | **`UNIQUE (chave)`**: é ela que decide quem chegou primeiro; `CHECK (status IN ('EM_PROCESSAMENTO','CONCLUIDA'))`; `resposta` nula até concluir; `expira_em` = criação + 24 h. |
  > | `pagamento` | `id uuid`, `order_id uuid`, `valor numeric(12,2)`, `status text`, `version bigint` | **`UNIQUE (order_id)`**: um pagamento por pedido, a segunda trava; `CHECK (status IN ('PENDENTE','APROVADO','RECUSADO','EXPIRADO'))`. |
  >
  > **Escolha de negócio: cartão recusado encerra a compra.** A chave é da intenção e o pagamento é único por pedido, então um `402` é resposta final daquela chave: repetir devolve o mesmo `402`; trocar o cartão com a mesma chave muda o corpo e dá `422`. Para tentar outro cartão, o cliente começa um **checkout novo**, com chave nova (nova intenção) e pedido novo. Aceitamos isso pela simplicidade: um pedido, uma cobrança. Várias tentativas no mesmo pedido ficam para quando o negócio pedir (DOR).
  >
  > ```mermaid
  > sequenceDiagram
  >   participant C as Cliente
  >   participant API as Checkout API
  >   participant DB as Banco
  >   participant P as Parceiro
  >   C->>API: POST /checkout + Idempotency-Key K
  >   API->>DB: INSERT K (UNIQUE), sem linha de pagamento
  >   alt K já existe
  >     API-->>C: resposta guardada / 409 / 422
  >   else K nova
  >     API->>P: cobrança + Idempotency-Key K (bulkhead, CB, timeout)
  >     alt aprovado ou recusado
  >       API->>DB: INSERT pagamento APROVADO/RECUSADO, guarda resposta
  >       API-->>C: 201 / 402
  >     else timeout ou 500 (ambíguo: pode ter cobrado)
  >       API->>DB: INSERT pagamento PENDENTE
  >       API-->>C: 202 PENDENTE
  >       Note over API,P: reconciliação consulta P pela chave e fecha
  >     else 503 e o parceiro não foi chamado
  >       API->>DB: libera K, nenhuma linha em pagamento
  >       API-->>C: 503 + Retry-After
  >     end
  >   end
  > ```

## 3. Os Edge Cases (Engenharia do Caos)

Cole aqui as 3 perguntas de risco que você formulou e defina como a arquitetura responde a cada uma delas.

1. **E se...** o parceiro ficar lento sem chegar a dar timeout (1,8 s por resposta) e milhares de compras ficarem esperando ao mesmo tempo, travando o checkout inteiro?
   - **Resposta do Sistema:** Threads virtuais aguentam a espera; o limite real é o pool de conexões com o banco. Por isso nenhuma conexão nem transação fica aberta durante a chamada ao parceiro (grava só a chave numa transação curta, chama o parceiro sem conexão, grava o resultado em outra transação curta). `PENDENTE` só nasce se o resultado for ambíguo. O Bulkhead deixa no máximo 200 chamadas simultâneas ao parceiro; quem não pega vaga em 50 ms recebe `503` + `Retry-After` sem cobrança e sem linha de pagamento, e essa rejeição não conta como falha do disjuntor. O Circuit Breaker conta também a lentidão (`slow-call-duration-threshold: 1500ms`, `slow-call-rate-threshold: 60`): abre mesmo sem erro e responde `503` rápido por 30 s, voltando aos poucos (5 chamadas de teste no meio-aberto).
2. **E se...** o disjuntor ficar aberto e, quando o parceiro volta, todos os clientes reenviarem ao mesmo tempo, derrubando o parceiro de novo?
   - **Resposta do Sistema:** A cota é uma só para a frota: cada réplica recebe `cota / réplicas` e o gateway segura o teto cheio. O excesso recebe `503` + `Retry-After` na nossa porta, sem chegar ao parceiro e sem contar como falha do disjuntor. Nossos retries (só `429`/`503` e conexão recusada, nunca timeout de leitura) usam espera crescente com sorteio (*jitter*), então as cópias não repetem no mesmo instante. O meio-aberto testa com 5 chamadas antes de fechar. O reenvio usa a mesma `Idempotency-Key`: sem cobrança dupla. Lacuna registrada: o `Retry-After` devolvido aos clientes é fixo; sorteá-lo (ex.: 20 a 40 s) espalharia também a volta dos apps.
3. **E se...** o cliente reenviar o mesmo checkout (duplo clique ou retry do app) e as duas requisições, com a mesma `Idempotency-Key`, chegarem ao mesmo tempo em réplicas diferentes?
   - **Resposta do Sistema:** As duas tentam o `INSERT` da chave; a constraint `UNIQUE` do banco deixa **uma** vencer. A vencedora chama o parceiro. A perdedora lê a linha: se ainda está `EM_PROCESSAMENTO`, responde `409` (o cliente tenta de novo depois e recebe a resposta guardada); se já concluiu, devolve a resposta guardada. Um lock em memória não resolveria, porque as réplicas não dividem memória. O `UNIQUE` em `order_id` no pagamento é a segunda trava.

## 4. Testando a Resiliência

- **Estratégia:** Como você vai garantir que o Circuit Breaker abre na hora certa e o Timeout não deixa a Thread presa?
  > **Ferramenta:** WireMock no lugar do parceiro, num teste de integração com o Spring de verdade (as anotações moram no proxy; teste de unidade com `new` não pega). Para falha de rede real, Testcontainers + Toxiproxy (latência, `reset_peer`). Cada teste **nasce vermelho**: escrito antes da configuração, tem de falhar sem ela (sem timeout, o teste de 10 s quebra). Verde no código errado é falha; cobertura alta não é garantia (TESTE).
  >
  > | Cenário injetado | O que o teste afirma |
  > |---|---|
  > | Latência de 10 s (`withFixedDelay(10_000)`) | A resposta volta em ~2 s (timeout + margem), com `202 PENDENTE`; nunca `201`. Depois, as vagas do bulkhead voltam ao máximo: a thread foi liberada. |
  > | 500 / conexão derrubada (`withFault(CONNECTION_RESET_BY_PEER)`) repetidos | Após `minimum-number-of-calls` com taxa acima do limite, o estado vai a `OPEN` (lido no `CircuitBreakerRegistry`). |
  > | Circuito aberto | A próxima chamada responde `503` em poucos ms e o WireMock **não recebe** requisição nova (`verify(exactly(n), ...)`). |
  > | Recuperação (no teste, `wait-duration-in-open-state` curto) | Stub volta a `200`, o estado passa a `HALF_OPEN` e, com as chamadas de teste ok, `CLOSED`. |
  > | 503 seguido de 200 (cenários do WireMock) | Houve retry e **cada tentativa levou a mesma chave**: `verify(postRequestedFor(urlEqualTo("/charges")).withHeader("Idempotency-Key", equalTo(k)))`, e nenhuma com chave diferente. Timeout: **exatamente 1** chamada ao parceiro. |
  > | Duas requisições simultâneas com a mesma chave | Uma `201`, uma `409`; o parceiro recebe 1 chamada. |
  > | Recusa (`402`) repetida | O circuito **continua** `CLOSED`: recusa não é falha técnica. |
  > | Recusa e depois outro cartão com a mesma chave | `422`; o WireMock **não recebe** nova cobrança. |
  >
  > **Limite do stub (PIRÂMIDE):** o WireMock devolve o que mandamos. Ele não prova que o parceiro real deduplica pela chave, nem como ele responde a um timeout. Por isso há também um teste de contrato no **sandbox do parceiro**: mesma chave duas vezes → uma cobrança só; e a consulta usada pela reconciliação devolve o estado esperado.
