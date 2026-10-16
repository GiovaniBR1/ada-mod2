# Perguntas de Risco: Aula 02 - Checkout e Pagamento Externo

**Cenário.** Temos uma loja online. A API de **Checkout** (o "caixa" do site) precisa aguentar **10.000 usuários ao mesmo tempo** e, para fechar cada compra, chama um **parceiro de pagamento externo** (ex.: Cielo, Stripe). O parceiro é instável: às vezes demora, às vezes devolve erro, às vezes derruba a conexão. E a cliente, quando a tela trava, aperta "pagar" de novo.

O que o nosso desenho (`system-design.md`) já tem, e que as respostas abaixo usam:

- **Spring MVC com threads virtuais:** cada compra roda numa thread barata, que pode ficar esperando o parceiro sem prender uma thread real do sistema.
- **Resilience4j** em volta da chamada ao parceiro: **timeout** no cliente HTTP (1 s para conectar, 2 s para responder), **Circuit Breaker** (o "disjuntor": se o parceiro falha demais, paramos de chamá-lo por 30 s), **Retry** restrito (repete só quando temos certeza de que o parceiro não recebeu nada), **Bulkhead** (no máximo 200 chamadas simultâneas ao parceiro) e **RateLimiter** (nunca passar da cota de chamadas por segundo do contrato com o parceiro).
- **`Idempotency-Key`:** um código único (UUID) que o app gera quando a cliente confirma a compra e reenvia igual em toda tentativa. No banco, a tabela `idempotency_keys` tem `UNIQUE (chave)`: duas gravações com a mesma chave são impossíveis.
- **Pagamento `PENDENTE` + reconciliação:** quando não sabemos se o parceiro cobrou, o pagamento fica `PENDENTE` e um job (uma tarefa automática que roda de tempos em tempos) consulta o parceiro e fecha em `APROVADO`, `RECUSADO` ou `EXPIRADO`.
- **Duas travas contra cobrança dupla:** `UNIQUE (chave)` em `idempotency_keys` e `UNIQUE (order_id)` em `pagamento` (um pagamento por pedido).

A regra que nenhum risco pode quebrar: **o dinheiro só sai uma vez, e uma falha nunca aparece como compra aprovada**.

Cada pergunta segue o mesmo roteiro: **a história** (quem, o quê, em que ordem), **o que dá errado por dentro** (tabelas, estados, chamadas), **como protegemos, passo a passo** e **como testamos**. Horários e valores são exemplos para deixar a história concreta.

---

## 1. E se o parceiro ficar **lento sem chegar a dar timeout** (1,8 s por resposta) e milhares de compras ficarem esperando ao mesmo tempo, travando o checkout inteiro?

### A história

Sexta-feira, 20h, começo de uma promoção. O parceiro de pagamento não cai: ele só fica **lento**. Cada cobrança leva **1,8 s**, logo abaixo do nosso limite de resposta de 2 s. Nada dá erro, então à primeira vista "está tudo funcionando".

| Hora | O que acontece |
|---|---|
| 20:00:00 | Chegam **3.000 compras por segundo**. Antes, o parceiro respondia em 200 ms. |
| 20:00:05 | O parceiro passa a responder em 1,8 s. Com 3.000 compras/s × 1,8 s de espera, há cerca de **5.400 compras esperando o parceiro ao mesmo tempo**. |
| 20:00:06 | A Elisa tenta comprar um carregador de **R$ 120**. Ela nem chega ao parceiro: o checkout dela fica parado esperando uma **conexão com o banco**. |
| 20:00:10 | Quem só queria consultar um pedido também espera. A loja inteira parece fora do ar, e o parceiro não devolveu nenhum erro. |

### O que dá errado por dentro

Três coisas se somam:

- **Threads virtuais não são o limite, e isso engana.** Com Spring MVC e threads virtuais, 5.400 compras esperando custam pouca memória. O servidor não trava por falta de threads, então o problema não aparece onde costumava aparecer.
- **O limite real é o pool de conexões com o banco.** O pool (HikariCP) vem com **10 conexões** por padrão. Se o código abrisse a transação do banco **antes** de chamar o parceiro e só a fechasse **depois** (um `@Transactional` em volta de tudo), cada compra seguraria uma conexão por 1,8 s. Com 10 conexões, o banco atenderia só ~5 compras por segundo; as outras 2.995 fariam fila esperando conexão, inclusive a Elisa e quem só quer consultar.
- **Lentidão abaixo do timeout não conta como falha.** O timeout de 2 s nunca dispara e nenhum erro aparece. Um Circuit Breaker que olhasse só os erros ficaria fechado, e continuaríamos mandando 3.000 chamadas por segundo para um parceiro que já está sufocando, piorando a lentidão dele.

### Como protegemos, passo a passo

1. **Nunca segurar conexão nem transação do banco durante a chamada ao parceiro.** O fluxo grava a chave e o pagamento `PENDENTE` numa transação curta (milissegundos), devolve a conexão ao pool, chama o parceiro **sem conexão nenhuma** e abre outra transação curta para gravar o resultado. Cada compra usa o banco por poucos milissegundos, não por 1,8 s: as 10 conexões dão conta, e a consulta de pedidos continua rápida.
2. **Bulkhead (o "limite de lotação") na chamada ao parceiro: no máximo 200 ao mesmo tempo.** É um semáforo do Resilience4j (`max-concurrent-calls: 200`). A compra nº 201 espera no máximo **50 ms** por uma vaga (`max-wait-duration: 50ms`); se não conseguir, recebe na hora `503` com `Retry-After`, **sem chamar o parceiro e sem cobrar nada**. Em vez de 5.400 chamadas empilhadas no parceiro, são no máximo 200 por cópia da aplicação.
3. **O Circuit Breaker também conta a lentidão.** A configuração tem `slow-call-duration-threshold: 1500ms` e `slow-call-rate-threshold: 60`: se 60% das últimas 50 chamadas levarem mais de 1,5 s, o disjuntor **abre**, mesmo sem nenhum erro. Com o parceiro a 1,8 s, isso acontece em poucos segundos.
4. **Disjuntor aberto: resposta rápida e honesta.** Por 30 s (`wait-duration-in-open-state`), o checkout responde `503` com `Retry-After` em poucos milissegundos, sem chamar o parceiro. O app mostra "pagamento temporariamente indisponível, tente em instantes" e reenvia depois com a **mesma** `Idempotency-Key`.
5. **Volta gradual.** Depois dos 30 s, o disjuntor fica "meio aberto" e deixa passar só **5 chamadas de teste**. Se forem rápidas, fecha; se ainda forem lentas, abre de novo por mais 30 s.
6. **Resultado para a Elisa:** ou ela compra (se pegou vaga e o parceiro respondeu), ou recebe `503` em milissegundos. Ela nunca fica parada esperando o banco por causa da lentidão de outra pessoa.

⚠️ **Valor a calibrar:** o desenho usa 200 vagas no Bulkhead e o pool padrão de 10 conexões como **valores iniciais**. Os números certos saem de um teste de carga com a latência real do parceiro.

### Como testamos

Teste de integração com o Spring de verdade (as anotações do Resilience4j só funcionam pelo proxy do Spring) e o WireMock no lugar do parceiro:

1. **Lentidão abaixo do timeout abre o disjuntor:** configurar o WireMock para responder `200` "aprovado" com **atraso de 1,8 s** e fazer 30 chamadas.
   **Conferir:** nenhuma deu erro de timeout; depois de 20 chamadas lentas (o `minimum-number-of-calls`), o estado lido no `CircuitBreakerRegistry` é `OPEN`; a chamada seguinte responde `503` em menos de 100 ms e o WireMock **não recebe** chamada nova.
2. **Bulkhead cheio:** no teste, baixar o limite para 5 vagas e disparar 20 compras ao mesmo tempo (threads soltas juntas por um `CountDownLatch`) com o WireMock a 1,8 s.
   **Conferir:** no máximo 5 chamadas chegaram ao WireMock ao mesmo tempo; as outras receberam `503` em até ~50 ms; nenhuma foi cobrada.
3. **O banco não é segurado durante a espera:** com o pool reduzido a **2 conexões** e o WireMock a 1,8 s, disparar 10 compras e, no meio, uma consulta `GET /pedidos/{id}`.
   **Conferir:** a consulta responde em menos de 200 ms; a métrica `hikaricp.connections.pending` fica em 0 durante a espera pelo parceiro.
4. **Contraprova:** colocar um `@Transactional` em volta da chamada ao parceiro. O teste 3 tem de ficar vermelho: a consulta espera conexão.

---

## 2. E se o disjuntor ficar aberto por um tempo e, quando o parceiro volta, **todos os clientes reenviarem ao mesmo tempo**, derrubando o parceiro de novo?

### A história

Sábado, 14h. O parceiro saiu do ar por 2 minutos. O disjuntor abriu e milhares de clientes receberam `503` com `Retry-After: 30` ("tente de novo em 30 segundos"). O Fábio estava comprando um fone de **R$ 250**.

| Hora | O que acontece |
|---|---|
| 14:00:00 | O parceiro cai. Em segundos, o disjuntor de cada cópia da aplicação abre. |
| 14:00:00–14:02:00 | **12.000 clientes**, entre eles o Fábio, recebem `503` com `Retry-After: 30`. O app de cada um agenda uma nova tentativa. |
| 14:02:00 | O parceiro volta, ainda frágil: aguenta umas **500 cobranças por segundo** (a cota do nosso contrato). |
| 14:02:01 | Os apps que receberam `503` nos últimos 30 s reenviam **juntos**: uns **6.000 pedidos no mesmo segundo**. |
| 14:02:02 | O parceiro, recém-recuperado, recebe 12 vezes mais do que aguenta, começa a devolver `429` ("muitas requisições") e `503`, e cai de novo. O disjuntor reabre. O ciclo se repete a cada 30 s. |

### O que dá errado por dentro

Esse é o efeito "manada": todos esperaram o **mesmo** tempo e voltam no **mesmo** instante. Por dentro:

- **O meio-aberto protege pouco por cópia.** Cada cópia do Checkout deixa passar 5 chamadas de teste (`permitted-number-of-calls-in-half-open-state: 5`). Com muitas cópias, e com as chamadas que entram assim que cada disjuntor fecha, o pico chega inteiro ao parceiro.
- **Nossos próprios retries somam.** O Retry do Resilience4j repete `429`/`503` do parceiro até 3 vezes. Se todas as cópias repetirem com o mesmo intervalo, cada onda vira três ondas.
- **A cota do contrato estoura.** Passando de 500 por segundo, o parceiro pode bloquear a nossa loja por mais tempo, como punição prevista em contrato.
- **Para o Fábio:** em vez de comprar às 14:02, ele toma `503` atrás de `503` e desiste. Não há cobrança dupla (a `Idempotency-Key` cuida disso), mas a venda se perde.

### Como protegemos, passo a passo

1. **RateLimiter na saída, com a cota do contrato.** O Resilience4j `RateLimiter` deixa sair no máximo a cota contratada (ex.: 500 cobranças/s no total, dividida entre as cópias). O que passar disso recebe `503` com `Retry-After` **na nossa porta**, sem chegar ao parceiro. O parceiro nunca vê o pico.
2. **Retry com espera crescente e sorteio (*jitter*).** Nossos retries de `429`/`503` esperam 200 ms, depois ~400 ms, depois ~800 ms (`enable-exponential-backoff`), e cada espera tem uma parte **sorteada** (`enable-randomized-wait`). Duas cópias não repetem no mesmo milissegundo, e a onda se espalha no tempo.
3. **Retry só quando é seguro.** O Retry só repete quando o parceiro disse claramente que **não processou** (`429`, `503`) ou quando a conexão nem abriu. Um timeout de leitura nunca é repetido: vira `PENDENTE` e vai para a reconciliação, que consulta o parceiro aos poucos.
4. **Meio-aberto com poucas chamadas de teste.** Cada cópia testa com 5 chamadas antes de fechar o disjuntor. Se o parceiro ainda estiver frágil, o disjuntor reabre sem ter mandado uma enxurrada.
5. **Reenvio sem cobrança dupla.** Quando o Fábio reenvia, o app usa a **mesma** `Idempotency-Key`. Se a primeira tentativa recebeu `503` (nada foi cobrado), a chave foi liberada e a compra segue normalmente; se alguma tentativa ficou `PENDENTE`, ele recebe o estado atual, sem nova chamada.

⚠️ **Lacuna do desenho atual:** o `system-design.md` manda `Retry-After` com um valor fixo para todos. Para espalhar de verdade a volta dos **clientes** (não só dos nossos retries internos), o `Retry-After` também deveria ser sorteado (ex.: entre 20 e 40 s), e o app deveria esperar mais a cada novo `503`. Registrado aqui sem mudar o desenho.

### Como testamos

Teste de integração com o Spring e o WireMock:

1. **O RateLimiter segura o pico:** configurar no teste um limite de 10 chamadas por segundo e disparar 100 compras no mesmo instante, com o WireMock aprovando tudo.
   **Conferir:** o WireMock recebeu no máximo ~10 chamadas naquele segundo; as outras receberam `503` com `Retry-After`; nenhuma foi cobrada duas vezes.
2. **Retry espalhado:** usar os "cenários" do WireMock (respostas em sequência) para devolver `429` duas vezes e `200` na terceira, e registrar o horário de cada tentativa.
   **Conferir:** foram 3 tentativas, **todas com a mesma `Idempotency-Key`**; os intervalos crescem (~200 ms, ~400 ms) e, em 20 repetições do teste, nunca são idênticos (prova do sorteio).
3. **Recuperação gradual:** abrir o disjuntor com falhas, trocar o WireMock para `200` e esperar o tempo de aberto (encurtado no teste).
   **Conferir:** no estado meio-aberto passam **exatamente 5** chamadas ao WireMock antes de o estado virar `CLOSED`.
4. **Contraprova:** desligar o sorteio (`enable-randomized-wait: false`). Em várias cópias simuladas, as tentativas passam a cair no mesmo instante.

---

## 3. E se a cliente reenviar o mesmo checkout (duplo clique ou retry do app) e as duas requisições, com a mesma `Idempotency-Key`, chegarem ao mesmo tempo em réplicas diferentes?

### A história

Sábado, 10h. A Daniela compra um tênis de **R$ 600**. A tela demora, ela clica "pagar" duas vezes em seguida. O app manda as duas requisições com a mesma chave `K-dani-1`. O balanceador de carga (quem distribui as requisições entre as réplicas) manda uma para cada réplica.

| Hora | O que acontece |
|---|---|
| 10:00:00.000 | Requisição 1 chega na réplica A. |
| 10:00:00.003 | Requisição 2 chega na réplica B. |
| 10:00:00.010 | As duas querem saber se a chave `K-dani-1` já existe. |

### O que dá errado por dentro

O jeito ingênuo é "conferir e depois gravar", em dois passos:

```sql
SELECT 1 FROM idempotency_keys WHERE chave = 'K-dani-1';  -- as duas recebem "não existe"
INSERT INTO idempotency_keys (...) VALUES ('K-dani-1', ...);  -- as duas gravam
```

Entre o `SELECT` de uma e o `INSERT` dela, a outra também fez o `SELECT`. As duas passam pelo `if`, as duas chamam o parceiro, cada uma achando que é a primeira: a Daniela paga **R$ 1.200**. Uma trava em memória (`synchronized`, `ReentrantLock`) não resolve, porque as réplicas A e B são processos diferentes, em máquinas diferentes: uma não enxerga a memória da outra.

### Como protegemos, passo a passo

1. **Um passo só, e quem decide é o banco.** Não existe `SELECT` antes. As duas réplicas tentam direto o `INSERT` da chave com status `EM_PROCESSAMENTO`. Como a tabela tem `UNIQUE (chave)`, o PostgreSQL deixa **exatamente uma** gravar. A outra recebe erro de violação de chave única (no Spring, `DataIntegrityViolationException`).
2. **A vencedora (digamos, a réplica A)** grava também o pagamento `PG-dani` como `PENDENTE`, chama o parceiro e, ao receber "aprovado", grava `APROVADO` e guarda a resposta `201` na linha da chave (`status = 'CONCLUIDA'`, `resposta_status = 201`, `resposta` com o corpo).
3. **A perdedora (réplica B)** lê a linha da chave:
   - se ainda está `EM_PROCESSAMENTO` (a réplica A está esperando o parceiro), responde `409` ("já estamos processando"), **sem chamar o parceiro**;
   - se já está `CONCLUIDA`, devolve a resposta guardada, com o mesmo status (`201`).
4. **O app trata o `409`**: espera um pouco e repete com a mesma chave; recebe o `201` guardado. A Daniela vê uma compra só.
5. **Se a réplica A cair no meio**, a linha da chave tem `travado_ate` (ex.: agora + 30 s). Vencido esse prazo, o pagamento já está gravado como `PENDENTE` e a reconciliação assume: consulta o parceiro pela chave e fecha o pagamento.
6. **Segunda trava:** mesmo que um bug deixasse passar, `UNIQUE (order_id)` em `pagamento` impede um segundo pagamento para o mesmo pedido.

**Escolha de negócio ligada a este risco:** se a cliente trocar o cartão e reenviar com a **mesma** chave, o corpo da requisição muda (o `hash_do_corpo` não bate) e a resposta é `422`. Para usar outro cartão, ela começa um checkout novo, com chave nova.

### Como testamos

1. Configurar o WireMock para aprovar com **atraso de 500 ms** (para a primeira requisição ainda estar `EM_PROCESSAMENTO` quando a segunda chegar).
2. Disparar duas requisições com a mesma chave `K1` **ao mesmo tempo**, em duas threads, liberadas juntas por um `CountDownLatch` (um "portão" que solta as duas threads no mesmo instante).
3. **Conferir:** uma resposta `201` e uma `409`; o WireMock recebeu **exatamente 1** cobrança; a tabela `idempotency_keys` tem 1 linha para `K1`; `pagamento` tem 1 linha.
4. Repetir a chamada com `K1` depois de terminar: volta o `201` guardado, e o WireMock continua com 1 cobrança.
5. **Repetir o teste 100 vezes** (com chaves diferentes por rodada): em nenhuma rodada pode haver 2 cobranças.
6. **Contraprova:** trocar o `INSERT` direto por "`SELECT` e depois `INSERT`"; o teste tem de ficar vermelho em algumas rodadas.

Observação: o teste roda dentro de uma só aplicação, com duas threads. Ele prova a regra do banco (que é o que decide), mas não simula duas máquinas de verdade. Como a decisão está no `UNIQUE` do PostgreSQL, isso basta.
