# System Design: Aula 04 - Microsserviços & Padrão Saga

> **Cenário.** Três serviços, três bancos. Checkout guarda pedidos. Pagamento fala com o PSP (o provedor do Pix). Estoque guarda a unidade. O cliente pagou a TV e o estoque acabou. A carga é 5.000 checkouts por segundo, e milhões abrem "Meus Pedidos".

A chave contra o clique duplo, o disjuntor e o timeout do parceiro continuam. O estoque continua no `UPDATE` condicional, com bulkhead antes do banco. `PENDENTE` na linha de pagamento é só timeout ou 500.

Fluxos: [anexo](anexo-detalhes.md). Nomes: [apêndice](apendice-maquina-de-estados.md).

## 1. Transações Distribuídas: Coordenação e Trade-offs

### 1.1 Volumetria e Pragmatismo: 2PC vs Saga

- **Análise Crítica:**
  > Aqui a compra é uma saga. Saga: cada passo grava no seu banco e tem um desfazer.
  > Com 20 compras por dia, um serviço e um Postgres na mesma transação bastam.
  > 2PC (as duas fases do commit) só cabe em poucos bancos, na mesma rede, todos preparados, e com volume baixo.
  > O PSP é HTTP. O Kafka também não entra. O lock ficaria preso até todo mundo confirmar. Se o coordenador cai, as linhas ficam travadas.
  > Um lock de 4 ms é a hipótese da Aula 3: cerca de 250 compras por segundo num produto (1000 ÷ 4). No 2PC a espera sobe e o pool acaba. A troca é a transação local curta.

### 1.2 O Modelo de Coordenação da Saga

- **A Escolha:** ( ) Coreografia (Orientada a Eventos) (X) Orquestração (Process Manager / Máquina de Estados)
- **Justificativa e Mitigação das Desvantagens do seu Modelo:**
  > A escolha é orquestração. A saga mora no Checkout.
  > O Checkout manda o comando pelo Kafka e lê a resposta. A mensagem nasce na outbox, na mesma transação do dado. Outbox: a tabela de saída. A chave é `pedido_id`.
  > Há dinheiro, e o desfazer tem ordem. Um lugar sabe o passo, o prazo e o que desfazer. HTTP preso seguraria thread e conexão. Coreografia espalharia "onde está o pedido 123?".
  > O Estoque decide a unidade. O Pagamento decide cobrar e estornar. Ninguém lê o banco do outro.
  > Se o fluxo crescer, a próxima ferramenta é o Temporal. Hoje bastam `status`, `version` e `prazo_passo`.

## 2. Máquina de Estados, Compensação e Confiabilidade

### 2.1 Máquina de Estados e Fluxos Ponta a Ponta

- **Estados do Pedido:**
  > Reservamos antes de cobrar. Reserva e QR vencem juntos, em 15 min. Sem unidade, para sem cobrar.
  > Os doze estados: `PENDENTE`, `ESTOQUE_EM_VERIFICACAO`, `AGUARDANDO_PAGAMENTO`, `PAGAMENTO_EM_VERIFICACAO`, `PAGO`, `CONFIRMACAO_EM_VERIFICACAO`, `CONCLUIDO`, `CANCELADO_SEM_COBRANCA`, `ESTORNANDO`, `ESTORNO_EM_REVISAO`, `ESTORNO_FALHOU`, `CANCELADO_COM_ESTORNO`.
  > Sem estoque termina `CANCELADO_SEM_COBRANCA`. Pix devolvido termina `CANCELADO_COM_ESTORNO`.
  > A troca exige o estado anterior. Zero linhas é mensagem velha. A exceção do Pix atrasado está no [apêndice](apendice-maquina-de-estados.md#linha-de-pagamento).
  > `PAGO` espera o passo seguinte, por exemplo 30 s. Sem prazo, um pedido cobrado fica cobrado para sempre. [Apêndice](apendice-maquina-de-estados.md).
- **Detalhamento dos Fluxos:**
  > O feliz vai de `PENDENTE` a `CONCLUIDO`: reserva, cobra, confirma. O QR nasce `AGUARDANDO_CAPTURA`. O nome do evento do Pix é só `PagamentoAprovado`, sem um segundo token. A reserva fica `CONFIRMADA`.
  > No incidente, `ReservaRejeitada` leva a `ESTORNANDO`. A linha de pagamento continua `APROVADO`. A tabela `estorno` vai a `CONCLUIDO`.
  > `SagaCompra.java` grava `ESTORNADO` num mapa. A simulação não tem a tabela `estorno`.
  > O relógio de `prazo_passo` reenvia o mesmo `message_id` (`pedido_id` mais o tipo do passo). Confirmada vira `CONCLUIDO`. Rejeitada vai para `ESTORNANDO`. Desconhecida vai para `CONFIRMACAO_EM_VERIFICACAO`, sem estornar no escuro. [Anexo, fluxos](anexo-detalhes.md#fluxos).

### 2.2 Resiliência da Compensação e Consistência

- **E se a compensação falhar ou oscilar?**
  > Um estorno por pagamento, do valor total. A chave no PSP é o id do pagamento. `UNIQUE (pagamento_id)` impede o segundo.
  > A chamada HTTP do estorno espera no máximo 3 s. Timeout não diz se o PSP devolveu. Antes de repetir, consulta o estorno pela mesma chave. Esse 3 s não é a espera de 5 s em `proxima_tentativa_em`.
  > As esperas ficam em `proxima_tentativa_em`: 1 s, 5 s, 30 s, 2 min e 10 min. Um poll curto alcança a de 1 s.
  > Na quinta falha, vai para `ESTORNO_EM_REVISAO`, com mensagem na DLQ (a fila do que não fechou) e um chamado.
  > 24 h sem confirmação: `ESTORNO_FALHOU`. O job de 5 min só olha essa revisão e a consulta diária. Se o PSP confirmar, fecha `CANCELADO_COM_ESTORNO` sem outra devolução.
  > `PAGO` usa o `prazo_passo` (ex.: 30 s). Não entra nesse job.
- **Atomicidade entre Estado e Mensageria (Dual Write):**
  > A outbox nasce na mesma transação. O Debezium (o programa que lê a caixa de saída e publica) publica no Kafka (a fila de mensagens). O serviço não fala com o Kafka.
  > Gravar e publicar depois perde o comando se a rede cai. Publicar antes cria evento de algo que não foi gravado.
  > A entrega é pelo menos uma vez. O consumidor grava o `message_id` junto com o efeito. O job reapresenta o mesmo id.
  > O feliz tem 7 mensagens: `ReservarEstoque`, `EstoqueReservado`, `CobrarPagamento`, `PagamentoAprovado`, `ConfirmarReserva`, `ReservaConfirmada` e `PedidoConcluido`. A 5.000 pedidos/s, 5.000 × 7 = 35.000 mensagens/s. A outbox é particionada por dia. Apaga a partição, não a linha.

### 2.3 Modelo de Dados (o que a Saga acrescenta)

> Cada tabela mora no banco do seu serviço. `estorno_pendente` é verdadeiro só em `ESTORNANDO`, `ESTORNO_EM_REVISAO` e `ESTORNO_FALHOU`.
> `CHECK` do pagamento: `PENDENTE`, `AGUARDANDO_CAPTURA`, `APROVADO`, `RECUSADO`, `EXPIRADO`. O do estorno: `SOLICITADO`, `CONCLUIDO`, `FALHOU`, no máximo 5 tentativas. [Anexo, modelo](anexo-detalhes.md#modelo).

## 3. Desacoplamento com CQRS e Estratégia de Testes

### 3.1 Consultas em Alta Vazão com CQRS

- **O Problema de Leitura:** milhões de usuários atualizam "Meus Pedidos" a cada poucos segundos.
- **Desenho da Solução:**
  > "Meus Pedidos" lê o Redis, pela chave `customer_id`. CQRS: quem grava e quem responde a tela não são o mesmo modelo.
  > Dois milhões de clientes a cada 5 s são 400 mil leituras/s. Isso é 80 vezes as escritas.
  > Um consumidor do Kafka grava `pedidos:{customer_id}` no Redis quando `version` é maior. A meta de atraso é 1 s no p99.
  > Se a cópia estiver atrás, o app mostra "processando seu pagamento". Se o Redis cair, a tela mostra "atualizando" e a compra continua.

### 3.2 Estratégia de Testes da Transação Distribuída

- **Validação de Resiliência e Plano de Testes:**
  > O teste sobe Kafka, Debezium e um Postgres por serviço, com WireMock no PSP (servidor falso). O caso é "Pix aprovado, estoque rejeita". Espera até 10 s, sem `sleep` fixo, em todo PR da saga.
  > `ReservaRejeitada` termina `CANCELADO_COM_ESTORNO`, com 1 devolução ao PSP. A tela mostra "Cancelado, Pix devolvido". A DLQ fica vazia.
  > PSP sempre 503 fica em `ESTORNO_EM_REVISAO` e, após 24 h, em `ESTORNO_FALHOU`. De `CONCLUIDO` e de `CANCELADO_COM_ESTORNO` não se sai. A exceção do Pix atrasado está no [apêndice](apendice-maquina-de-estados.md#linha-de-pagamento).
  > `SagaCompra.java` imprime esses dois cancelamentos. Não substitui Kafka nem Postgres. [Anexo, testes](anexo-detalhes.md#testes).
