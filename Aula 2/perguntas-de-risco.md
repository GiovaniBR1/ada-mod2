# Perguntas de Risco: Aula 02 - Checkout e Pagamento Externo

As respostas usam o `system-design.md`: threads virtuais, timeout de 2 s no cliente HTTP, disjuntor, retry só quando a chamada não chegou, bulkhead de 200, rate limit da cota do contrato e `Idempotency-Key` com `UNIQUE`. A regra: o dinheiro sai uma vez, e falha nunca aparece como compra aprovada.

Cada pergunta responde direto, depois o porquê, depois um exemplo e o teste.

---

## 1. E se o parceiro ficar lento sem chegar a dar timeout (1,8 s) e milhares de compras ficarem esperando ao mesmo tempo?

**Resposta.** A lentidão não segura o banco nem vira fila infinita no parceiro. A conexão do banco não atravessa a chamada, o bulkhead corta em 200, e o disjuntor abre por chamada lenta, sem contar a rejeição do próprio bulkhead.

**Por quê.** Threads virtuais aguentam a espera, então o servidor não trava por falta de thread. O limite real é o pool de conexões (HikariCP, 10 por padrão). Se a transação ficasse aberta durante os 1,8 s, cada compra seguraria uma conexão e a loja inteira esperaria o banco. Abaixo do timeout de 2 s não há erro: um disjuntor que só olhasse falha ficaria fechado e continuaria empurrando carga para um parceiro já lento. A rejeição do bulkhead (`BulkheadFullException`) acontece antes da chamada. Contá-la como falha abriria o circuito por causa da nossa própria fila.

**Exemplo.** 3.000 compras/s × 1,8 s ≈ 5.400 esperas. Com a transação em milissegundos, as 10 conexões dão conta. A chamada 201 ao parceiro espera no máximo 50 ms, recebe `503` com `Retry-After`, sem linha de pagamento e sem somar no disjuntor. Com 60% das últimas 50 chamadas acima de 1,5 s, o circuito abre e responde `503` em milissegundos por 30 s.

**Como testamos.** Integração com Spring e WireMock.

1. WireMock a 1,8 s com `200`, 30 chamadas. Depois de 20 lentas, o `CircuitBreakerRegistry` está `OPEN`. A seguinte volta `503` em menos de 100 ms e o WireMock não recebe chamada nova.
2. Bulkhead de 5 vagas e 20 compras juntas. No máximo 5 chegam ao WireMock. As outras recebem `503` em ~50 ms, sem cobrança e sem abrir o circuito.
3. Pool de 2 conexões, WireMock a 1,8 s, 10 compras e um `GET /pedidos/{id}` no meio. A consulta responde em menos de 200 ms e `hikaricp.connections.pending` fica em 0.
4. Contraprova: `@Transactional` em volta da chamada ao parceiro. O teste 3 fica vermelho.

---

## 2. E se o disjuntor abrir e, quando o parceiro volta, todos os clientes reenviarem ao mesmo tempo?

**Resposta.** A cota do contrato é um número só para a frota. Cada réplica recebe `cota / réplicas` no RateLimiter local, e o gateway segura o teto cheio. O excesso toma `503` na nossa porta. O retry espalha no tempo. A mesma chave impede cobrança dupla.

**Por quê.** O RateLimiter do Resilience4j conta dentro do processo. Dez réplicas com a cota inteira mandam dez vezes o contrato e derrubam o parceiro de novo. O meio-aberto (5 chamadas de teste por réplica) não segura esse pico. `RequestNotPermitted` também não conta como falha do disjuntor: o parceiro não foi chamado.

**Exemplo.** Contrato de 500/s, 10 réplicas, 50/s em cada uma e 500/s no gateway. 6.000 reenvios no mesmo segundo: 500 seguem, o resto recebe `503` sem chegar ao parceiro. Os retries internos esperam ~200 ms, ~400 ms, ~800 ms, com uma parte sorteada, e só repetem `429`, `503` ou conexão que nem abriu. Timeout de leitura vira pagamento `PENDENTE`, não uma segunda cobrança.

**Como testamos.**

1. Limite de 10/s e 100 compras juntas. O WireMock recebe cerca de 10 naquele segundo. O resto volta `503` com `Retry-After`, sem cobrança dupla e sem abrir o circuito.
2. WireMock devolve `429` duas vezes e `200` na terceira. Três tentativas, a mesma `Idempotency-Key`, intervalos crescentes e, em 20 rodadas, intervalos que não são idênticos.
3. Circuito aberto, stub volta a `200`. No meio-aberto passam exatamente 5 chamadas antes de `CLOSED`.
4. Contraprova: `enable-randomized-wait: false`. As tentativas voltam a cair no mesmo instante.

O `Retry-After` devolvido ao app ainda é fixo. Sorteá-lo (20 a 40 s) espalharia também a volta dos clientes. Está registrado no desenho e não muda a divisão da cota.

---

## 3. E se duas requisições com a mesma `Idempotency-Key` chegarem ao mesmo tempo em réplicas diferentes?

**Resposta.** As duas fazem `INSERT` da chave. O `UNIQUE` deixa uma gravar. A outra não chama o parceiro.

**Por quê.** "SELECT e depois INSERT" perde a corrida: as duas leem "não existe" e as duas cobram. Um lock em memória não atravessa réplicas. A linha de pagamento não nasce no `INSERT` da chave. Ela nasce depois, quando o resultado existe: estado final, ou `PENDENTE` se a chamada saiu e a resposta foi ambígua.

**Exemplo.** Duas requisições com a chave `K` no mesmo milissegundo, uma em cada réplica. Uma recebe `201` e o parceiro vê uma cobrança. A outra lê `EM_PROCESSAMENTO` e responde `409`, ou devolve o `201` guardado se a primeira já concluiu. Se a réplica vencedora cair depois de enviar e antes de gravar, `travado_ate` vence e a reconciliação consulta o parceiro pela mesma chave: se a cobrança existe, grava o pagamento; se não existe, libera a chave.

**Como testamos.**

1. WireMock aprova com atraso de 500 ms. Duas threads com a mesma chave, soltas juntas por um `CountDownLatch`.
2. Uma resposta `201`, uma `409`. O WireMock recebeu uma cobrança. Uma linha na chave, uma em `pagamento`.
3. Repetir a chave depois de concluir devolve o `201` guardado, ainda com uma cobrança.
4. Cem rodadas, chaves diferentes: nenhuma com duas cobranças.
5. Contraprova: trocar o `INSERT` por SELECT e depois INSERT. O teste fica vermelho em parte das rodadas.

O teste roda numa aplicação só, com duas threads. A decisão está no `UNIQUE` do PostgreSQL, então isso basta. Corpo diferente com a mesma chave responde `422` e não chama o parceiro: outro cartão é outro checkout, com chave nova.
