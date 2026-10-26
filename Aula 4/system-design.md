# System Design: Aula 04 - Microsserviços & Padrão Saga

> **Cenário.** É o mesmo checkout das Aulas 2 e 3, agora dividido em três serviços, cada um com o seu banco: **Checkout** (pedidos), **Pagamento** (Pix, via um PSP, o provedor de pagamento externo) e **Estoque**. Não existe mais um `@Transactional` que cubra os três.
>
> **O incidente.** Um cliente pagou uma Smart TV de R$ 3.000 via Pix. O Pagamento confirmou; no milissegundo seguinte, o Estoque rejeitou a reserva porque o produto esgotou. Cliente cobrado, produto inexistente, pedido travado.
>
> **A carga.** 5.000 checkouts por segundo no pico e milhões de clientes atualizando "Meus Pedidos" a cada poucos segundos.
>
> **O que não muda das aulas anteriores:** a `Idempotency-Key` contra o clique duplo, o disjuntor e o timeout no parceiro, o `PENDENTE` da **linha de pagamento** só no timeout ou 500 da chamada síncrona (Aula 2; não é o estado do pedido), e o `UPDATE` atômico do estoque com Bulkhead por produto antes do banco (Aula 3). A regra da linha, o `CHECK` e a única exceção a "estado final não muda" (`EXPIRADO` → `APROVADO` quando o webhook de captura chega tarde, e o pedido em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO`) estão na [regra única](apendice-maquina-de-estados.md#linha-de-pagamento). Os nomes do pedido estão no [apêndice](apendice-maquina-de-estados.md), compartilhado com as Aulas 5 e 6.

**Glossário curto.** *Saga*: uma compra dividida em passos locais, cada um com um "desfazer" (a **compensação**). *Outbox*: tabela onde o serviço grava o evento junto com o dado, na mesma transação. *Broker*: o carteiro das mensagens entre serviços (aqui, Kafka). *CQRS*: separar o modelo que grava do modelo que responde consultas. *Idempotente*: fazer duas vezes dá o mesmo resultado que fazer uma.

Fluxos numerados, a tabela do modelo e os cenários de teste estão no [anexo](anexo-detalhes.md).

---

## 1. Transações Distribuídas: Coordenação e Trade-offs

### 1.1 Volumetria e Pragmatismo: 2PC vs Saga

- **Análise Crítica:**
  > Decisão: aqui é Saga. Com 20 compras por dia, um serviço e um PostgreSQL no mesmo `@Transactional` bastam, sem Kafka e sem CQRS. O 2PC só cabe em poucos bancos, na mesma rede, todos com XA, e volume baixo.
  > Por quê: o PSP é HTTP e não aceita prepare/commit; o Kafka também não entra em XA. O lock do estoque ficaria preso do prepare ao commit de todos. Se o coordenador cai entre as fases, as linhas ficam travadas.
  > Exemplo: na Aula 3, X ≈ 4 ms dá cerca de 250 compras/s (`1000 ÷ X`). No 2PC, X ≈ 20 ms cai para cerca de 50/s (hipótese, não medida). A 5.000 TPS o pool esgota em segundos. A troca: transação local curta e consistência eventual ("pago, confirmando estoque").

### 1.2 O Modelo de Coordenação da Saga

- **A Escolha:** ( ) Coreografia (Orientada a Eventos) (X) Orquestração (Process Manager / Máquina de Estados)
- **Justificativa e Mitigação das Desvantagens do seu Modelo:**
  > Decisão: orquestração. A Saga de Compra fica dentro do Checkout. Fala por comandos e respostas no Kafka, via outbox. A chave da mensagem é `pedido_id`.
  > Por quê: há dinheiro e a compensação tem ordem. Um lugar sabe o passo, o prazo e o desfazer. HTTP síncrono prenderia thread e conexão, o problema da Aula 2 vezes três. Coreografia espalha "onde está o pedido 123?".
  > Exemplo: o orquestrador só conhece a sequência e o desfazer. O Estoque decide a unidade (Bulkhead e `UPDATE` atômico). O Pagamento decide cobrar e estornar. Não lê o banco dos outros. Se o fluxo crescer, a próxima ferramenta é o Temporal. Hoje bastam `status`, `version` e `prazo_passo` na tabela de pedidos.

---

## 2. Máquina de Estados, Compensação e Confiabilidade

### 2.1 Máquina de Estados e Fluxos Ponta a Ponta

- **Estados do Pedido:**
  > Decisão: reservar o estoque antes de cobrar. A reserva e o QR do Pix vencem juntos (ex.: 15 min).
  > Por quê: sem unidade, a compra para sem cobrar. O incidente ainda cabe no último segundo: o Pix cai, a reserva expirou e a TV foi para outra pessoa.
  > Exemplo: sem estoque termina `CANCELADO_SEM_COBRANCA`. Pix devolvido termina `CANCELADO_COM_ESTORNO`. Não há "cancelado com Pix retido". A transição é `UPDATE pedidos SET status = :novo, version = version + 1 WHERE id = :id AND status = :esperado`. Zero linhas é mensagem velha, com uma exceção: Pix em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO` e `pagamento.status` sai de `EXPIRADO` para `APROVADO`. `PAGO` tem `prazo_passo` (ex.: 30 s).
  > Diagrama: [apêndice](apendice-maquina-de-estados.md).
- **Detalhamento dos Fluxos:**
  > Decisão: o caminho feliz reserva, cobra e confirma. A falha devolve o Pix sem mudar o status do pagamento para outro valor.
  > Por quê: a reserva pode expirar entre o Pix e a confirmação. O dinheiro que entrou depois do cancelamento sem cobrança também precisa voltar.
  > Exemplo: feliz vai de `PENDENTE` a `CONCLUIDO`, com QR `AGUARDANDO_CAPTURA`, `PagamentoAprovado` e reserva `CONFIRMADA`. No incidente, `ReservaRejeitada` leva a `ESTORNANDO`. A linha de pagamento permanece `APROVADO` e a tabela `estorno` vai a `CONCLUIDO`. Em `SagaCompra.java` o laboratório grava `ESTORNADO` no mapa em memória do pagamento, porque a simulação não tem a tabela `estorno`. Pix tarde em `CANCELADO_SEM_COBRANCA` vai a `ESTORNANDO` na hora. O job reenvia o mesmo `message_id` (`pedido_id` + tipo do passo): reserva confirmada vira `CONCLUIDO`; rejeitada, `ESTORNANDO`; desconhecida, `CONFIRMACAO_EM_VERIFICACAO`, sem estornar no escuro.
  > Passos: [anexo, fluxos](anexo-detalhes.md#fluxos).

### 2.2 Resiliência da Compensação e Consistência

- **E se a compensação falhar ou oscilar?**
  > Decisão: um estorno por pagamento, do valor total. A chave enviada ao PSP é o id do pagamento. `UNIQUE (pagamento_id)`.
  > Por quê: timeout não diz se o PSP devolveu. Antes de repetir, o Pagamento consulta o estorno pela mesma chave. Dez repetições devolvem o dinheiro uma vez.
  > Exemplo: esperas de 1 s, 5 s, 30 s, 2 min e 10 min. Depois da quinta, `ESTORNO_EM_REVISAO`, DLQ e chamado; o cliente vê "devolução em análise". 24 h sem confirmação: `ESTORNO_FALHOU`. O job consulta o PSP uma vez por dia e, se confirmar, fecha `CANCELADO_COM_ESTORNO` sem uma segunda devolução.
- **Atomicidade entre Estado e Mensageria (Dual Write):**
  > Decisão: outbox na mesma transação local. O Debezium lê o WAL e publica no Kafka. O serviço não fala com o Kafka.
  > Por quê: gravar e depois publicar perde o comando se a rede cai. Publicar antes cria evento de algo que não foi gravado.
  > Exemplo: a entrega é pelo menos uma vez. O consumidor grava `message_id` em `mensagens_processadas` (PK) na mesma transação do efeito. O job reapresenta o mesmo id. O caminho feliz tem 6 mensagens entre os serviços mais `PedidoConcluido`: 7. A 5.000 TPS, cerca de 35 mil mensagens/s. A outbox é particionada por dia; apaga com `DROP` da partição, não com `DELETE`.

### 2.3 Modelo de Dados (o que a Saga acrescenta)

> Decisão: cada tabela mora no banco do seu serviço. IDs `uuid` v7 e status `text` com `CHECK`, como na Aula 3.
> Por quê: o Checkout não lê o banco do Pagamento. A trava do estorno é uma linha por pagamento.
> Exemplo: `estorno_pendente` é verdadeiro somente em `ESTORNANDO`, `ESTORNO_EM_REVISAO` e `ESTORNO_FALHOU`, e falso em todos os outros estados. O `CHECK` do pagamento é `PENDENTE`, `AGUARDANDO_CAPTURA`, `APROVADO`, `RECUSADO`, `EXPIRADO`. O do estorno é `SOLICITADO`, `CONCLUIDO`, `FALHOU`, com no máximo 5 tentativas. Os doze estados do pedido estão no apêndice.
> Tabela: [anexo, modelo](anexo-detalhes.md#modelo).

---

## 3. Desacoplamento com CQRS e Estratégia de Testes

### 3.1 Consultas em Alta Vazão com CQRS

- **O Problema de Leitura:** milhões de usuários atualizam "Meus Pedidos" a cada poucos segundos.
- **Desenho da Solução:**
  > Decisão: "Meus Pedidos" lê a projeção `meus_pedidos` no Redis (chave `customer_id`), não o banco do Checkout.
  > Por quê: 2 milhões de clientes a cada 5 s são 400 mil leituras/s, 80 vezes as escritas. A réplica do banco de pedidos continua acoplada à escrita e não aguenta esse volume.
  > Exemplo: o consumidor só aplica evento com `version` maior. Meta de atraso: 1 s no p99, pelo lag do Kafka. Se a projeção estiver atrás da `version` que o checkout devolveu, o app mostra "processando seu pagamento". Push por SSE no lugar de perguntar a cada 2 s. Se o Redis cair, a tela mostra "atualizando" e a compra continua.

### 3.2 Estratégia de Testes da Transação Distribuída

- **Validação de Resiliência e Plano de Testes:**
  > Decisão: JUnit 5, Testcontainers (Kafka, Debezium e um PostgreSQL por serviço) e WireMock no PSP. O teste principal é "Pix aprovado, estoque rejeita".
  > Por quê: a saga atravessa processos. Awaitility espera até 10 s, sem `sleep` fixo. Esses testes rodam em todo PR da Saga e ficam separados dos testes de unidade.
  > Exemplo: 1 unidade já vendida, Pix confirmado e `ReservaRejeitada` terminam `CANCELADO_COM_ESTORNO`, com exatamente 1 devolução (chave = id do pagamento), projeção "Cancelado, Pix devolvido" e DLQ vazia. PSP sempre `503` fica em `ESTORNO_EM_REVISAO` e, após 24 h, em `ESTORNO_FALHOU`. De `CONCLUIDO` e de `CANCELADO_COM_ESTORNO` não se sai. De `CANCELADO_SEM_COBRANCA` só se sai se o Pix chegar depois. `desafio/` imprime `CANCELADO_SEM_COBRANCA` sem estoque e `CANCELADO_COM_ESTORNO` com Pix devolvido; não substitui Kafka e Postgres.
  > Tabela: [anexo, testes](anexo-detalhes.md#testes).
