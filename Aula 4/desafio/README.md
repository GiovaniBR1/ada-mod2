# Desafio de Código (opcional): Saga, Outbox e compensação

**O que pede.** A trilha prática da Aula 4 não pede código próprio: indica laboratórios oficiais para rodar e observar. O **Booking SAGA do Temporal** (compensação automática vista na Web UI), o **Outbox do Debezium** (Postgres + CDC + Kafka sem dupla escrita) e o tutorial da **Confluent** de Spring Boot com DLQ e CQRS. Não é avaliado: o entregável oficial é o System Design.

**O que tem aqui.** Os laboratórios precisam de Docker e do servidor do Temporal, que não temos na box. Em vez de só descrever, fizemos uma **simulação em Java puro** (sem bibliotecas) com as mesmas ideias, para provar que as regras do `system-design.md` fecham:
- `SagaCompra.java`: três serviços com "bancos" separados, outbox em cada um, um relay que publica (o papel do Debezium), consumidores idempotentes, o orquestrador no Checkout e a projeção de "Meus Pedidos" (CQRS).
- `saida-execucao.txt`: a saída real da execução.

Para rodar (Java 21): `java SagaCompra.java`.

## Os seis cenários e o que provam

| Cenário | Resultado | O que prova |
|---|---|---|
| 1. Caminho feliz | `CONCLUIDO`, reserva `CONFIRMADA` | A sequência reservar, cobrar, confirmar. |
| 2. O incidente: Pix aprovado, reserva já expirada e vendida | `PAGO` → `ESTORNANDO` → `CANCELADO`, 1 devolução | A compensação dispara sozinha. |
| 3. Estorno falha 2 vezes e passa na 3ª | `CANCELADO`, **1** devolução | Repetir não devolve em dobro (chave = id do pagamento). |
| 4. Estorno falha sempre | `ESTORNO_EM_REVISAO` após 5 tentativas, pagamento ainda `APROVADO` | Nunca "cancelado" sem o dinheiro voltar. |
| 5. Relay cai depois de publicar | Mensagem repetida no broker, descartada; `CANCELADO`, 1 devolução | A outbox não perde evento, e a entrega repetida é inofensiva. |
| 6. Sem estoque desde o início | `CANCELADO`, nenhum pagamento | Reservar antes de cobrar evita a maioria dos estornos. |

## Limites (o que a simulação não mostra)

- Tudo roda numa thread, em memória: não há concorrência real, rede, nem o banco travando. Isso continua sendo papel do teste com Testcontainers descrito no `system-design.md` (seção 3.2).
- A "espera crescente" entre tentativas de estorno é só registrada no log, não esperada de verdade.
- A ordem das mensagens é a da fila; o caso de evento fora de ordem na projeção (pergunta de risco 3) não está simulado.
