# Template de System Design: Aula 02 - APIs Resilientes

> **Instruções:** Faça uma cópia deste arquivo e preencha as seções abaixo com as conclusões da sua pesquisa e os debates. O foco é a integração da API de Checkout com o Serviço de Pagamento Externo para suportar 10.000 usuários simultâneos.

YAML, tabelas, o diagrama e os cenários de teste estão no [anexo](anexo-detalhes.md). A linha de pagamento segue a [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento).

## 1. Escolha de Paradigma da API

- **A Escolha:** (x) Spring MVC com Virtual Threads ou ( ) Spring WebFlux (Reativo)?
- **Justificativa:** Considerando a capacidade de suportar 10k acessos concorrentes, por que você fez essa escolha técnica?
  > Decisão: Spring MVC com threads virtuais (`spring.threads.virtual.enabled=true`, Spring Boot 3.2+, Java 21).
  > Por quê: a carga é espera de I/O, não CPU. O código segue imperativo, com JDBC e Resilience4j. WebFlux foi descartado: não há streaming nem R2DBC. Os números "30-40%" e "~1000 threads" do material não têm cenário.
  > Exemplo: 10 mil esperas custam memória, não 10 mil threads de SO. O limite passa a ser o pool (HikariCP vem com 10) e a cota do parceiro. Nenhuma conexão fica aberta durante a chamada. No Java 21, `synchronized` em volta de I/O prende a thread virtual; usar `ReentrantLock` (o JDK 24 resolveu, JEP 491).
  > Detalhe: [anexo, paradigma](anexo-detalhes.md#paradigma).

## 2. Desenho de Resiliência

Dependemos de uma API terceira instável. Quais recursos do padrão de resiliência você vai utilizar?

- **Proteção contra Falhas e Lentidão:**
  Quais padrões (Circuit Breaker, TimeLimiter, RateLimiter, Bulkhead, Retry) você implementaria na chamada para o Parceiro de Pagamentos? Por que?
  > Decisão: timeout no cliente HTTP, Circuit Breaker, Retry restrito, Bulkhead e Rate Limiter. TimeLimiter não entra no caminho síncrono.
  > Por quê: a anotação do TimeLimiter não interrompe a chamada bloqueante. Timeout de leitura e HTTP 500 são ambíguos e não se repetem. Recusa `402`, `BulkheadFullException` e `RequestNotPermitted` não abrem o disjuntor: o parceiro não foi chamado.
  > Exemplo: `connectTimeout` 1 s e resposta 2 s. Bulkhead de 200, espera de vaga 50 ms. Retry no máximo 3, só `ConnectException` e 503/429, mesma chave. Contrato de 500/s e 10 réplicas: 50/s em cada uma e 500/s no gateway. Circuito aberto, bulkhead cheio ou cota estourada: `503` + `Retry-After`, sem linha de pagamento. Timeout ou 500 depois de enviar: `202` com `PENDENTE`; a reconciliação fecha só com `WHERE status = 'PENDENTE'`. A única exceção é a regra única: `EXPIRADO` → `APROVADO`, e o pedido em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO`.
  > Ordem, tabela e YAML: [anexo, padrões](anexo-detalhes.md#padroes).

- **Proteção contra Duplicidade:**
  Como a `Idempotency-Key` será utilizada na requisição do Checkout para evitar cobranças duplas? Qual o ciclo de vida dessa chave?
  > Decisão: a chave é da intenção. O cliente gera um UUID e reenvia a mesma. Sem o cabeçalho: `400`.
  > Por quê: o `INSERT` com `UNIQUE (chave)` decide quem chegou primeiro. A mesma chave vai ao parceiro. `UNIQUE (order_id)` é a segunda trava.
  > Exemplo: mesma chave e mesmo hash já concluído devolve 201, 402 ou 202. Corpo diferente: `422`. Ainda `EM_PROCESSAMENTO`: `409`. 503 sem chamada libera a chave e não cria pagamento. Timeout ou 500 grava `PENDENTE`. TTL de 24 h. Se a réplica cair, `travado_ate` vence: com cobrança no parceiro, a reconciliação insere a linha (final ou `PENDENTE`); sem cobrança, libera a chave. Cartão recusado encerra; outro cartão é checkout novo. O `CHECK` de `pagamento.status` é `PENDENTE`, `AGUARDANDO_CAPTURA`, `APROVADO`, `RECUSADO`, `EXPIRADO`.
  > Ciclo, tabelas e diagrama: [anexo, idempotência](anexo-detalhes.md#idempotencia).

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
  > Decisão: WireMock no Spring de verdade, e Testcontainers + Toxiproxy para falha de rede. Cada teste nasce vermelho.
  > Por quê: as anotações moram no proxy; `new` não as pega. O stub não prova que o parceiro real deduplica.
  > Exemplo: latência de 10 s volta em ~2 s com `202 PENDENTE`, nunca `201`, e as vagas do bulkhead voltam. Depois do mínimo de chamadas o circuito vai a `OPEN`; a próxima responde `503` e o WireMock não recebe. Timeout gera exatamente 1 chamada. Duas requisições com a mesma chave: uma `201` e uma `409`. Recusa `402` deixa o circuito `CLOSED`. Outro cartão com a mesma chave: `422`. No sandbox, a mesma chave cobra uma vez.
  > Tabela: [anexo, testes](anexo-detalhes.md#testes).
