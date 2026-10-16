# Perguntas de Risco: Aula 02 - Checkout e Pagamento Externo

**Cenário.** Temos uma loja online. A API de **Checkout** (o "caixa" do site) precisa aguentar **10.000 usuários ao mesmo tempo** e, para fechar cada compra, chama um **parceiro de pagamento externo** (ex.: Cielo, Stripe). O parceiro é instável: às vezes demora, às vezes devolve erro, às vezes derruba a conexão. E a cliente, quando a tela trava, aperta "pagar" de novo.

O que o nosso desenho (`system-design.md`) já tem, e que as respostas abaixo usam:

- **Spring MVC com threads virtuais:** cada compra roda numa thread barata, que pode ficar esperando o parceiro sem prender uma thread real do sistema.
- **Resilience4j** em volta da chamada ao parceiro: **timeout** no cliente HTTP (1 s para conectar, 2 s para responder), **Circuit Breaker** (o "disjuntor": se o parceiro falha demais, paramos de chamá-lo por 30 s), **Retry** restrito (repete só quando temos certeza de que o parceiro não recebeu nada) e **Bulkhead** (no máximo 200 chamadas simultâneas ao parceiro).
- **`Idempotency-Key`:** um código único (UUID) que o app gera quando a cliente confirma a compra e reenvia igual em toda tentativa. No banco, a tabela `idempotency_keys` tem `UNIQUE (chave)`: duas gravações com a mesma chave são impossíveis.
- **Pagamento `PENDENTE` + reconciliação:** quando não sabemos se o parceiro cobrou, o pagamento fica `PENDENTE` e um job (uma tarefa automática que roda de tempos em tempos) consulta o parceiro e fecha em `APROVADO`, `RECUSADO` ou `EXPIRADO`.
- **Duas travas contra cobrança dupla:** `UNIQUE (chave)` em `idempotency_keys` e `UNIQUE (order_id)` em `pagamento` (um pagamento por pedido).

A regra que nenhum risco pode quebrar: **o dinheiro só sai uma vez, e uma falha nunca aparece como compra aprovada**.

Cada pergunta segue o mesmo roteiro: **a história** (quem, o quê, em que ordem), **o que dá errado por dentro** (tabelas, estados, chamadas), **como protegemos, passo a passo** e **como testamos**. Horários e valores são exemplos para deixar a história concreta.

---

## 1. E se der timeout na chamada ao parceiro **depois** que ele já cobrou o cartão, e o retry cobrar a cliente duas vezes?

### A história

Terça-feira, 20h. A Ana compra um fone de **R$ 450** no cartão. O app gera a chave `K-ana-1`.

| Hora | O que acontece |
|---|---|
| 20:00:00.000 | O Checkout recebe `POST /checkout` com `Idempotency-Key: K-ana-1`. Grava a chave como `EM_PROCESSAMENTO` e o pagamento `PG-1` como `PENDENTE`. |
| 20:00:00.050 | O Checkout chama o parceiro: "cobre R$ 450 do cartão da Ana", mandando também `K-ana-1`. |
| 20:00:01.200 | O parceiro **cobra o cartão**. A fatura da Ana já tem R$ 450. |
| 20:00:01.300 | A resposta "aprovado" fica presa na rede do parceiro, sobrecarregado. |
| 20:00:02.050 | Passam os 2 s de limite de resposta. O cliente HTTP do Checkout desiste: **timeout de leitura**. |

### O que dá errado por dentro

O timeout é **ambíguo**: o Checkout não sabe se o parceiro cobrou. Dois jeitos errados de reagir:

- **Repetir na hora, como se fosse nova:** o Retry chama o parceiro de novo. Se a segunda chamada não tiver nada que diga "é a mesma compra", o parceiro cobra **outros R$ 450**. A Ana paga R$ 900 por um fone.
- **Desistir como se tivesse falhado:** o Checkout grava `pagamento.status = 'RECUSADO'` e responde "pagamento não aprovado". A Ana foi cobrada, não tem pedido e tenta comprar de novo (agora com chave nova): mais R$ 450.

### Como protegemos, passo a passo

1. **Timeout de leitura não entra no Retry.** A configuração do Resilience4j só repete `ConnectException` (a conexão nem abriu, então o parceiro não recebeu nada) e respostas `503`/`429` explícitas do parceiro. Timeout de leitura e erro `500` ficam de fora, porque o parceiro pode ter cobrado.
2. **O pagamento fica `PENDENTE`, não `RECUSADO`.** O Checkout não mexe em `PG-1` (continua `PENDENTE`) e responde `202 { "status": "PENDENTE" }`. O app mostra "estamos confirmando seu pagamento".
3. **Se a Ana apertar "pagar" de novo**, o app reenvia a **mesma** chave `K-ana-1`. O `INSERT` em `idempotency_keys` bate no `UNIQUE (chave)`. O Checkout lê a linha, vê que ela está ligada a `PG-1` e devolve o estado atual (`202 PENDENTE`). **Nenhuma chamada nova ao parceiro.**
4. **A reconciliação descobre a verdade.** Às 20:01:00, o job procura pagamentos `PENDENTE` há mais de 30 s (`WHERE status = 'PENDENTE'`) e **consulta** o parceiro pela chave `K-ana-1`. O parceiro responde "aprovado".
5. **Fecha uma vez só:** `UPDATE pagamento SET status = 'APROVADO' WHERE id = 'PG-1' AND status = 'PENDENTE'`. A chave vira `CONCLUIDA` com a resposta `201` guardada. Na próxima vez que o app perguntar, a Ana vê "compra aprovada".
6. **Se o parceiro disser que nunca recebeu a cobrança**, o job marca `EXPIRADO` (ou tenta de novo com a **mesma** chave). E se em algum caminho a chamada for repetida, o parceiro também deduplica pela chave: são duas travas, a nossa e a dele.

### Como testamos

Teste de integração com o Spring de verdade e o WireMock no lugar do parceiro:

1. Configurar o WireMock para responder "aprovado" com **atraso de 10 s** (`withFixedDelay(10_000)`).
2. Chamar `POST /checkout` com a chave `K1`.
3. **Conferir:** a resposta volta em cerca de 2 s com `202 PENDENTE`, nunca `201`; o WireMock recebeu **exatamente 1** cobrança; `PG-1` está `PENDENTE` no banco.
4. Chamar de novo com a mesma `K1`: volta `202`, e o WireMock **continua** com 1 cobrança.
5. Trocar o stub de consulta para "aprovado" e rodar a reconciliação.
6. **Conferir:** `PG-1` está `APROVADO`; uma terceira chamada com `K1` devolve `201`; o WireMock ainda tem 1 cobrança só.
7. **Contraprova:** com a regra do Retry desligada (repetindo timeout), o teste tem de ficar vermelho (o WireMock recebe 3 cobranças).

O WireMock não prova que o parceiro **real** deduplica pela chave. Isso fica para um teste no **sandbox** (ambiente de testes) do parceiro: mesma chave duas vezes, uma cobrança só.

---

## 2. E se o banco de dados cair na hora de registrar o estado de falha (o estado do Circuit Breaker ou o resultado do pagamento)?

### A história

Sexta-feira, 19h. O parceiro está doente e o banco da loja também sofre. O Bruno compra uma mochila de **R$ 300** com a chave `K-bruno-1`; a Carla compra um livro de **R$ 80** com `K-carla-1`.

| Hora | O que acontece |
|---|---|
| 19:00:00 | O parceiro começa a devolver erro `500` em metade das chamadas. |
| 19:00:20 | Na réplica A do Checkout, 20 chamadas seguidas, 60% falharam: o Circuit Breaker da réplica A **abre**. A réplica B ainda está com o disjuntor fechado (viu menos falhas). |
| 19:00:30 | O banco PostgreSQL fica fora do ar por 40 s (troca de servidor). |
| 19:00:31 | O Bruno chega na réplica B. O Checkout tenta gravar a chave e o pagamento `PENDENTE`: o banco não responde. |
| 19:00:35 | A Carla tinha chegado às 19:00:29, antes da queda: a chave e o `PENDENTE` foram gravados e o parceiro **aprovou** às 19:00:34. Agora o Checkout tenta gravar `APROVADO`, e o banco não responde. |
| 19:01:10 | O banco volta. |

### O que dá errado por dentro

São três perigos diferentes:

- **O disjuntor depender do banco:** se o estado "aberto/fechado" do Circuit Breaker morasse no banco, a queda do banco apagaria a proteção. As réplicas voltariam a bombardear o parceiro doente no pior momento.
- **O Bruno ser cobrado sem registro:** se o Checkout chamasse o parceiro **antes** de conseguir gravar o `PENDENTE`, haveria uma cobrança que o nosso banco não conhece. Ninguém nunca a reconciliaria.
- **A Carla pagar e o sistema "esquecer":** o parceiro aprovou, mas o `UPDATE pagamento SET status = 'APROVADO'` falhou. Se o Checkout respondesse "ok" mesmo assim, ou respondesse "recusado", o estado ficaria errado.

### Como protegemos, passo a passo

1. **O estado do disjuntor mora na memória de cada réplica, não no banco.** O Resilience4j guarda as últimas 50 chamadas e o estado (fechado, aberto, meio-aberto) dentro do próprio processo. A queda do banco não mexe nele: a réplica A continua com o disjuntor aberto e responde `503` rápido, sem chamar o parceiro.
2. **Escolha consciente: cada réplica tem a sua visão.** A réplica B ainda chama o parceiro por mais algumas falhas, até o disjuntor dela abrir também. Aceitamos isso: guardar o estado num lugar compartilhado (ex.: Redis) seria mais uma peça que pode cair. A visão do conjunto vem da métrica `resilience4j.circuitbreaker.state` de cada réplica, com alerta no painel.
3. **Bruno: nada de cobrar sem antes registrar.** A ordem no código é fixa: primeiro grava a chave e o `PENDENTE`; só depois chama o parceiro. Como a gravação falhou, o Checkout responde `503` com `Retry-After: 5` e **não chama o parceiro**. Na dúvida, nega. O app repete depois com a mesma `K-bruno-1`; com o banco de volta, a compra segue normalmente.
4. **Carla: falha nunca vira "ok".** O `UPDATE` para `APROVADO` falhou, então o Checkout responde `500` (erro), nunca `201`. No banco, `PG-carla` continua `PENDENTE` (foi gravado antes da queda).
5. **A reconciliação fecha quando o banco volta.** Às 19:01:30, o job encontra `PG-carla` `PENDENTE`, consulta o parceiro pela chave `K-carla-1`, recebe "aprovado" e faz `UPDATE ... SET status = 'APROVADO' WHERE status = 'PENDENTE'`. Se a Carla tentar de novo antes disso, a mesma chave devolve `202 PENDENTE`, sem nova cobrança.

### Como testamos

1. **Disjuntor sem banco:** com o WireMock devolvendo `500`, fazer chamadas até o disjuntor abrir. Parar o container do PostgreSQL (Testcontainers). Fazer mais 10 chamadas.
   **Conferir:** todas respondem `503` em poucos milissegundos; o estado lido no `CircuitBreakerRegistry` continua `OPEN`; o WireMock **não recebe** chamada nova.
2. **Banco fora antes do parceiro (o caso do Bruno):** parar o PostgreSQL e chamar `POST /checkout`.
   **Conferir:** resposta `503` com `Retry-After`; o WireMock recebeu **0** cobranças.
3. **Banco cai depois da aprovação (o caso da Carla):** configurar o WireMock para aprovar com atraso de 1 s e, durante esse segundo, parar o PostgreSQL.
   **Conferir:** a resposta é `500`, nunca `201`. Religar o banco e rodar a reconciliação: o pagamento termina `APROVADO`, e o WireMock tem 1 cobrança só.

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
5. **Se a réplica A cair no meio**, a linha da chave tem `travado_ate` (ex.: agora + 30 s). Vencido esse prazo, o pagamento já está gravado como `PENDENTE` e a reconciliação assume, como no risco 1.
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
