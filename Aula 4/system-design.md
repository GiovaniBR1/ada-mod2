# System Design: Aula 04 - Microsserviços & Padrão Saga

> **Cenário.** É o mesmo checkout das Aulas 2 e 3, agora dividido em três serviços, cada um com o seu banco: **Checkout** (pedidos), **Pagamento** (Pix, via um PSP, o provedor de pagamento externo) e **Estoque**. Não existe mais um `@Transactional` que cubra os três.
>
> **O incidente.** Um cliente pagou uma Smart TV de R$ 3.000 via Pix. O Pagamento confirmou; no milissegundo seguinte, o Estoque rejeitou a reserva porque o produto esgotou. Cliente cobrado, produto inexistente, pedido travado.
>
> **A carga.** 5.000 checkouts por segundo no pico e milhões de clientes atualizando "Meus Pedidos" a cada poucos segundos.
>
> **O que não muda das aulas anteriores:** a `Idempotency-Key` contra o clique duplo, o disjuntor e o timeout no parceiro, o `PENDENTE` da **linha de pagamento** só no timeout ou 500 da chamada síncrona (Aula 2; não é o estado do pedido), e o `UPDATE` atômico do estoque com Bulkhead por produto antes do banco (Aula 3). A regra da linha, o `CHECK` e a única exceção a "estado final não muda" (`EXPIRADO` → `APROVADO` quando o webhook de captura chega tarde, e o pedido em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO`) estão na [regra única](apendice-maquina-de-estados.md#linha-de-pagamento). Os nomes do pedido estão no [apêndice](apendice-maquina-de-estados.md), compartilhado com as Aulas 5 e 6.

**Glossário curto.** *Saga*: uma compra dividida em passos locais, cada um com um "desfazer" (a **compensação**). *Outbox*: tabela onde o serviço grava o evento junto com o dado, na mesma transação. *Broker*: o carteiro das mensagens entre serviços (aqui, Kafka). *CQRS*: separar o modelo que grava do modelo que responde consultas. *Idempotente*: fazer duas vezes dá o mesmo resultado que fazer uma.

---

## 1. Transações Distribuídas: Coordenação e Trade-offs

### 1.1 Volumetria e Pragmatismo: 2PC vs Saga

- **Análise Crítica:**
  > **Quando não precisa de Saga.** Com 20 compras por dia, a resposta certa é **um serviço e um PostgreSQL**: pedido, estoque e pagamento no mesmo `@Transactional`, como na Aula 3. Sem Kafka, sem Saga, sem CQRS. O **2PC** (*Two-Phase Commit*: um coordenador pergunta "todos prontos?" e só depois manda todos gravarem) também resolve quando são **poucos bancos, na mesma rede, todos com suporte a XA** (o protocolo do 2PC) e volume baixo, por exemplo um back-office que grava em dois bancos internos.
  >
  > **Por que o 2PC não serve aqui.** Três motivos, em ordem de peso:
  > 1. **O Pix não participa.** O PSP é uma API HTTP externa; ela não aceita "prepare" e "commit" de um coordenador nosso. O Kafka também não entra num 2PC XA. Então o 2PC nem cobriria o passo que causou o incidente.
  > 2. **Ele segura o lock mais tempo, justamente na linha mais disputada.** Na Aula 3 a linha do produto aguenta cerca de `1000 ÷ X` compras por segundo, com X = tempo que o lock fica preso (estimamos 4 ms, ≈ 250/s). No 2PC o lock do estoque fica preso do *prepare* até o *commit* de **todos**: duas idas e voltas na rede e uma gravação em disco por participante. Estimando X ≈ 20 ms, a mesma linha cai para ≈ **50 compras/s**. (Hipótese de ordem de grandeza, não medida.)
  > 3. **O coordenador vira ponto único de parada.** Se ele cai entre as duas fases, os participantes ficam com as linhas travadas esperando a decisão. A 5.000 TPS, isso esgota o pool de conexões em segundos.
  >
  > **A troca que fazemos:** cada serviço faz só a sua **transação local curta** (ACID dentro dele) e o conjunto fica **eventualmente consistente**: por alguns centésimos de segundo, o pedido pode estar "pago, confirmando estoque". O preço é ter de desenhar a compensação e os estados intermediários, que são as seções seguintes.

### 1.2 O Modelo de Coordenação da Saga

- **A Escolha:** ( ) Coreografia (Orientada a Eventos) (X) Orquestração (Process Manager / Máquina de Estados)
- **Justificativa e Mitigação das Desvantagens do seu Modelo:**
  > **Por que orquestração.** Há dinheiro envolvido e uma compensação com ordem certa (devolver o Pix, liberar o estoque, cancelar o pedido). Com orquestração existe **um lugar que sabe em que passo cada pedido está**, que controla os prazos e que dispara a compensação. Na coreografia, essa lógica fica espalhada em quem escuta o quê; com três serviços ainda dá, mas ao entrar antifraude, nota fiscal e frete, vira o "espaguete de eventos" e ninguém responde "onde está o pedido 123?".
  >
  > **Quem orquestra:** a **Saga de Compra**, um componente **dentro do serviço de Checkout**, porque o pedido é dele. O estado da Saga é a própria linha do pedido (`status`, `version`, `prazo_passo`).
  >
  > **Como fala com os outros: assíncrono, por comandos e respostas no Kafka**, publicados via outbox (2.2). Chave da mensagem = `pedido_id`, para que os eventos de um pedido cheguem em ordem. Síncrono (HTTP) foi descartado: o Checkout ficaria esperando o PSP e o Estoque com threads e conexões presas, o problema da Aula 2 multiplicado por três.
  >
  > **Como evitamos o "serviço Deus":** o orquestrador só conhece **a sequência e o desfazer de cada passo**. As regras de negócio ficam nos donos: o Estoque decide se há unidade (e mantém o Bulkhead por produto e o `UPDATE` atômico da Aula 3); o Pagamento decide como cobrar e estornar no PSP. O orquestrador não lê o banco de ninguém e não sabe calcular preço nem estoque. Se o fluxo crescer muito, a próxima ferramenta é um motor de workflow durável, como o **Temporal** (o laboratório da aula), que guarda o histórico de cada passo e refaz tentativas sozinho. Hoje, uma máquina de estados na tabela de pedidos basta.

---

## 2. Máquina de Estados, Compensação e Confiabilidade

### 2.1 Máquina de Estados e Fluxos Ponta a Ponta

- **Estados do Pedido:**
  > **Escolha de negócio: reservar o estoque antes de cobrar.** É o contrário do incidente: se não há unidade, a compra para **sem cobrar ninguém**. A reserva tem prazo (`expira_em`, ex.: 15 min) para o cliente pagar o Pix. O QR Code do Pix vence junto com a reserva. Mesmo assim o incidente ainda pode acontecer: o cliente paga no último segundo, a confirmação do PSP chega atrasada, e nesse meio-tempo a reserva expirou e a última TV foi para outra pessoa. É por isso que a compensação existe.
  >
  > O diagrama completo, o que aconteceu com `PENDENTE` e com `CANCELADO`, as cinco tentativas e o prazo de 24 h estão no [apêndice](apendice-maquina-de-estados.md). Resumo das saídas que o texto antigo juntava num `CANCELADO` só:
  >
  > ```mermaid
  > stateDiagram-v2
  >   [*] --> PENDENTE: checkout recebido
  >   PENDENTE --> AGUARDANDO_PAGAMENTO: estoque reservado (prazo 15 min)
  >   PENDENTE --> CANCELADO_SEM_COBRANCA: sem estoque (nada cobrado)
  >   PENDENTE --> ESTOQUE_EM_VERIFICACAO: reserva sem resposta no prazo
  >   ESTOQUE_EM_VERIFICACAO --> AGUARDANDO_PAGAMENTO: consulta achou a reserva
  >   ESTOQUE_EM_VERIFICACAO --> CANCELADO_SEM_COBRANCA: consulta diz que não reservou
  >   AGUARDANDO_PAGAMENTO --> PAGO: Pix confirmado
  >   AGUARDANDO_PAGAMENTO --> CANCELADO_SEM_COBRANCA: Pix não pago no prazo (reserva liberada)
  >   AGUARDANDO_PAGAMENTO --> PAGAMENTO_EM_VERIFICACAO: PSP sem resposta e consulta aberta
  >   PAGAMENTO_EM_VERIFICACAO --> PAGO: consulta diz pago
  >   PAGAMENTO_EM_VERIFICACAO --> CANCELADO_SEM_COBRANCA: consulta diz não pago
  >   PAGO --> CONCLUIDO: reserva confirmada
  >   PAGO --> ESTORNANDO: reserva rejeitada (expirou e esgotou)
  >   PAGO --> CONFIRMACAO_EM_VERIFICACAO: confirmação sem resposta no prazo
  >   CONFIRMACAO_EM_VERIFICACAO --> CONCLUIDO: consulta achou reserva confirmada
  >   CONFIRMACAO_EM_VERIFICACAO --> ESTORNANDO: consulta diz reserva rejeitada
  >   ESTORNANDO --> CANCELADO_COM_ESTORNO: Pix devolvido
  >   ESTORNANDO --> ESTORNO_EM_REVISAO: 5 tentativas sem confirmação
  >   ESTORNO_EM_REVISAO --> CANCELADO_COM_ESTORNO: PSP confirmou
  >   ESTORNO_EM_REVISAO --> ESTORNO_FALHOU: 24 h sem confirmação
  >   ESTORNO_FALHOU --> CANCELADO_COM_ESTORNO: consulta diária confirma
  >   CANCELADO_SEM_COBRANCA --> ESTORNANDO: Pix chegou depois do cancelamento
  >   CONCLUIDO --> [*]
  >   CANCELADO_COM_ESTORNO --> [*]
  > ```
  >
  > Cada transição é `UPDATE pedidos SET status = :novo, version = version + 1 WHERE id = :id AND status = :esperado`. Se 0 linhas mudaram, a mensagem é velha ou repetida e é ignorada, com uma exceção: Pix aprovado em `CANCELADO_SEM_COBRANCA` não é descarte (o dinheiro entrou) e `pagamento.status` sai de `EXPIRADO` para `APROVADO`, a única exceção a "estado final não muda" ([regra única](apendice-maquina-de-estados.md#linha-de-pagamento)). `CANCELADO_COM_ESTORNO` só existe **depois** de o dinheiro voltar. `CANCELADO_SEM_COBRANCA` só existe quando nada foi capturado. Não há estado "cancelado com Pix retido". `PAGO` tem `prazo_passo` (ex.: 30 s): sem resposta da reserva, o pedido não fica cobrado para sempre.
- **Detalhamento dos Fluxos:**
  > **Caminho feliz.**
  > 1. Checkout grava o pedido `PENDENTE` e o comando `ReservarEstoque` na outbox (uma transação).
  > 2. Estoque reserva (`UPDATE` atômico da Aula 3, reserva `ATIVA` com prazo) e grava `EstoqueReservado` na sua outbox.
  > 3. Orquestrador muda para `AGUARDANDO_PAGAMENTO` e emite `CobrarPagamento`. O Pagamento gera o QR Code do Pix e grava a linha `AGUARDANDO_CAPTURA`, com o `txid` do QR.
  > 4. O PSP avisa que o Pix caiu; o Pagamento grava `APROVADO` e `PagamentoAprovado`.
  > 5. Orquestrador muda para `PAGO` e emite `ConfirmarReserva`. Estoque muda a reserva para `CONFIRMADA` e responde `ReservaConfirmada`.
  > 6. Pedido `CONCLUIDO`.
  >
  > **Caminho de falha e compensação (o incidente).**
  > 1. No passo 5, o Estoque encontra a reserva já `LIBERADA` (expirou e a unidade foi vendida) e responde `ReservaRejeitada`.
  > 2. Orquestrador muda para `ESTORNANDO` e emite `EstornarPagamento`, na mesma transação.
  > 3. O Pagamento pede a **devolução do Pix** ao PSP, grava `ESTORNADO` e responde `PagamentoEstornado`.
  > 4. Orquestrador muda para `CANCELADO_COM_ESTORNO`. O cliente recebe a notificação "o produto esgotou, devolvemos seu Pix".
  >
  > **Pix que chega tarde.** Se a confirmação do PSP chega com o pedido já em `CANCELADO_SEM_COBRANCA` (venceu sem pagamento), o evento não é descartado como "atrasado": o dinheiro entrou. O orquestrador muda para `ESTORNANDO` e emite `EstornarPagamento` na hora, com a mesma chave. O fim, quando o PSP confirma, é `CANCELADO_COM_ESTORNO`.
  >
  > **Quem varre o que parou.** Cada passo feliz é disparado por um evento. Um **job de prazos** varre todo estado que não é fim com `prazo_passo` vencido, inclusive `PAGO` (ex.: 30 s sem resposta do Estoque). O job não grava mensagem nova: reenvia o **mesmo comando**, com o mesmo `message_id` (chave estável `pedido_id` + tipo do passo, não um uuid novo) e a mesma chave de negócio. Um id novo passaria na PK de `mensagens_processadas` e executaria o efeito outra vez. Em `PAGO`, o job consulta a reserva: confirmada vira `CONCLUIDO`; rejeitada vai para `ESTORNANDO`; desconhecida vai para `CONFIRMACAO_EM_VERIFICACAO` e não estorna enquanto o estoque for desconhecido.

### 2.2 Resiliência da Compensação e Consistência

- **E se a compensação falhar ou oscilar?**
  > 1. **Estorno idempotente, com escolha explícita da chave:** a chave de idempotência enviada ao PSP é o **id do pagamento**. Escolha de negócio: **um pagamento tem no máximo um estorno, sempre do valor total** (o pedido tem uma TV só; estorno parcial fica para quando existir carrinho com vários itens). No banco: `UNIQUE (pagamento_id)` na tabela `estorno`. Repetir o comando dez vezes devolve o dinheiro uma vez.
  > 2. **Timeout não é falha.** Se o PSP não responde, não sabemos se devolveu. Como no `PENDENTE` da Aula 2, antes de tentar de novo o Pagamento **consulta** o estorno no PSP pela mesma chave.
  > 3. **Tentativas com espera crescente:** 1 s, 5 s, 30 s, 2 min, 10 min. O disjuntor da Aula 2 protege o PSP de levar uma enxurrada de estornos na Black Friday.
  > 4. **Depois de 5 tentativas**, o pedido vai para `ESTORNO_EM_REVISAO`, a mensagem vai para a **DLQ** (a fila das mensagens que não deram certo) e um alerta abre um chamado. O cliente vê "devolução em análise", nunca um cancelamento com o Pix retido. As cinco tentativas continuam sendo o que abre a revisão. O prazo de **24 h** (apêndice) é o que a revisão ganhou para não ficar sem fim: sem confirmação, o estado passa a `ESTORNO_FALHOU`, o chamado segue aberto e o job consulta o PSP uma vez por dia. Se o PSP confirmar, fecha em `CANCELADO_COM_ESTORNO` sem uma segunda devolução.
- **Atomicidade entre Estado e Mensageria (Dual Write):**
  > **O problema.** "Grava no banco e depois publica no Kafka" são duas escritas. Se a rede cai entre as duas, o pedido fica `PAGO` e o comando de confirmar o estoque **nunca sai**: perda silenciosa. Inverter a ordem cria o oposto: evento de algo que não foi gravado.
  >
  > **A solução: Transactional Outbox.** O serviço grava o dado **e** a mensagem na tabela `outbox`, **na mesma transação local**. Ou as duas existem ou nenhuma. Quem publica é o **Debezium**: ele lê o diário de gravações do Postgres (o WAL) e manda as linhas novas da outbox para o Kafka. O código do serviço nem conhece o Kafka.
  >
  > **O efeito colateral: entrega "pelo menos uma vez".** Se o Debezium cai depois de publicar e antes de anotar até onde leu, ele publica de novo. Por isso **todo consumidor é idempotente**: grava o `message_id` em `mensagens_processadas` (PK) na mesma transação do efeito. Mensagem repetida bate na PK e é descartada. O job de prazos entra nessa regra: ele reapresenta o mesmo `message_id`. Um id novo seria outra mensagem, e a PK não a seguraria.
  >
  > **Volume.** O caminho feliz acima nomeia 6 mensagens entre os serviços (3 comandos e 3 respostas: `ReservarEstoque`, `EstoqueReservado`, `CobrarPagamento`, `PagamentoAprovado`, `ConfirmarReserva`, `ReservaConfirmada`). A sétima é `PedidoConcluido`, o evento da projeção. A 5.000 TPS, essas 7 são **≈ 35 mil mensagens/s** e 35 mil linhas novas por segundo nas outboxes. Para a tabela não crescer sem fim, ela é particionada por dia e as partições antigas são apagadas inteiras (`DROP` da partição é instantâneo; `DELETE` de milhões de linhas não é).

### 2.3 Modelo de Dados (o que a Saga acrescenta)

> Cada tabela mora no banco do seu serviço. IDs `uuid` v7 e status `text` com `CHECK`, como na Aula 3.
>
> | Serviço | Tabela | Campos principais | Constraints e regras |
> |---|---|---|---|
> | Checkout | `pedidos` | `id`, `customer_id`, `status`, `total numeric(12,2)`, `prazo_passo timestamptz`, `estorno_pendente boolean`, `version bigint` | `CHECK` com os doze estados do [apêndice](apendice-maquina-de-estados.md) (`PENDENTE`, `ESTOQUE_EM_VERIFICACAO`, `AGUARDANDO_PAGAMENTO`, `PAGAMENTO_EM_VERIFICACAO`, `PAGO`, `CONFIRMACAO_EM_VERIFICACAO`, `CONCLUIDO`, `CANCELADO_SEM_COBRANCA`, `ESTORNANDO`, `ESTORNO_EM_REVISAO`, `ESTORNO_FALHOU`, `CANCELADO_COM_ESTORNO`); `estorno_pendente` é verdadeiro em `ESTORNANDO`, `ESTORNO_EM_REVISAO` e `ESTORNO_FALHOU`, e falso só em `CANCELADO_COM_ESTORNO`; índice parcial em `prazo_passo` só para estados não finais (o job de prazos). |
> | Todos | `outbox` | `id`, `pedido_id`, `tipo`, `payload jsonb`, `criado_em` | Particionada por dia; gravada na mesma transação do dado. |
> | Todos | `mensagens_processadas` | `message_id`, `processada_em` | PK `message_id`: a trava contra mensagem repetida. |
> | Estoque | `reserva` | (Aula 3) `order_id`, `sku`, `status`, `expira_em` | `UNIQUE (order_id, sku)`; muda de `ATIVA` uma vez só. |
> | Pagamento | `pagamento` | `id`, `order_id`, `valor numeric(12,2)`, `status text`, `version bigint`, `txid text`, `e2e_id text` | `UNIQUE (order_id)`; `UNIQUE (txid)`: id do QR, o webhook acha a linha por ele; `UNIQUE (e2e_id)`: id do Pix no Banco Central, contra confirmação duplicada. `CHECK (status IN ('PENDENTE','AGUARDANDO_CAPTURA','APROVADO','RECUSADO','EXPIRADO'))`, o mesmo das Aulas 2 e 3. O que cada valor significa está na [regra única](apendice-maquina-de-estados.md#linha-de-pagamento). |
> | Pagamento | `estorno` | `id`, `pagamento_id`, `valor`, `status`, `tentativas int`, `proxima_tentativa_em` | **`UNIQUE (pagamento_id)`**: um estorno por pagamento; `CHECK (status IN ('SOLICITADO','CONCLUIDO','FALHOU'))`; `CHECK (tentativas <= 5)`. |

---

## 3. Desacoplamento com CQRS e Estratégia de Testes

### 3.1 Consultas em Alta Vazão com CQRS

- **O Problema de Leitura:** milhões de usuários atualizam "Meus Pedidos" a cada poucos segundos.
- **Desenho da Solução:**
  > **A conta.** Se 2 milhões de clientes atualizam a tela a cada 5 s, são **400 mil leituras/s**, 80 vezes as escritas do checkout. Mandar isso para o banco de pedidos é repetir o incidente do pool da Aula 3.
  >
  > **Escrita (Command):** o banco do Checkout e a Saga, como acima. Ninguém consulta "Meus Pedidos" nele.
  >
  > **Leitura (Query):** uma **projeção** `meus_pedidos` num armazenamento feito para ler por chave (Redis em cluster, chave `customer_id`, com o status já em texto para a tela e a `version` do pedido). Um consumidor escuta os mesmos eventos do Kafka e atualiza a projeção; ele também é idempotente e **só aplica um evento se a `version` for maior** que a guardada (evento atrasado não volta o status para trás). O Redis escala lendo de réplicas; se cair, a tela mostra "atualizando" e a compra continua, porque ele não está no caminho do dinheiro.
  >
  > **O atraso (consistência eventual).** Meta: a projeção atrasada no máximo 1 s (p99), medida pelo *lag* do consumidor no Kafka, com alerta. Para quem acabou de pagar e atualiza a tela:
  > - a resposta do checkout já traz `pedido_id` e `version`; o app guarda isso e, se a projeção mostrar uma `version` menor, exibe "processando seu pagamento" em vez de um status velho;
  > - em vez de a tela perguntar a cada 2 s, o app recebe o novo status por **push** (SSE), reduzindo as leituras repetidas.
  >
  > **Descartado:** ler da réplica do banco de pedidos. Ela continua acoplada ao modelo de escrita, atrasa sem controle e não aguenta 400 mil leituras/s.

### 3.2 Estratégia de Testes da Transação Distribuída

- **Validação de Resiliência e Plano de Testes:**
  > **Teste de integração principal: "Pix aprovado, estoque rejeita".** JUnit 5 com Testcontainers subindo **Kafka, Debezium e um PostgreSQL por serviço**, e o PSP no **WireMock**.
  > 1. *Dado* um produto com 1 unidade e uma reserva já expirada e vendida a outra pessoa;
  > 2. *quando* o WireMock confirma o Pix e o Estoque responde `ReservaRejeitada`;
  > 3. *então*, esperando com Awaitility (até 10 s, sem `sleep` fixo): o pedido termina `CANCELADO_COM_ESTORNO`; o WireMock recebeu **exatamente 1** pedido de devolução com a chave = id do pagamento; a projeção mostra "Cancelado, Pix devolvido"; nenhuma mensagem na DLQ.
  >
  > **Os testes de caos que provam a resiliência:**
  >
  > | Falha injetada | Como | O que deve acontecer |
  > |---|---|---|
  > | PSP oscila no estorno | WireMock responde `503` duas vezes, depois `200` | 1 devolução só; pedido `CANCELADO_COM_ESTORNO` |
  > | PSP fora do ar no estorno | WireMock sempre `503` | `ESTORNO_EM_REVISAO`, mensagem na DLQ, nunca `CANCELADO_COM_ESTORNO`; após 24 h, `ESTORNO_FALHOU`, ainda sem cancelar |
  > | Timeout do estorno | WireMock com atraso de 5 s | consulta antes de repetir; 1 devolução só |
  > | Relay cai depois de publicar | Reiniciar o container do Debezium no meio | mensagens repetidas são descartadas; o fluxo termina igual |
  > | Mensagem duplicada | Publicar o mesmo `message_id` duas vezes | o efeito acontece uma vez |
  >
  > **Na pipeline:** esses testes rodam em todo PR que mexe na Saga (levam minutos, não segundos, por isso ficam separados dos testes de unidade). A máquina de estados tem também testes de unidade para cada transição, incluindo as proibidas (de `CONCLUIDO` e de `CANCELADO_COM_ESTORNO` não se sai; de `CANCELADO_SEM_COBRANCA` só se sai se o Pix chegar depois).
  >
  > **Prova já feita:** a pasta `desafio/` tem uma simulação em Java puro com os seis cenários (caminho feliz, o incidente, estorno instável, estorno que sempre falha, relay que cai, sem estoque) e a saída real da execução. Ela não substitui o teste com Kafka e Postgres de verdade. Sem estoque o estado impresso é `CANCELADO_SEM_COBRANCA`; com Pix devolvido, `CANCELADO_COM_ESTORNO`.
