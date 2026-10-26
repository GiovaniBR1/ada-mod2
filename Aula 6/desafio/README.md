# Aula 6: desafio de código (observabilidade do checkout)

Projeto Spring Boot 4.1 mínimo. Ele implementa as regras que o system design descreve, com os mesmos números dos exemplos. Não sobe Grafana, Tempo nem o Astronomy Shop: isso é laboratório opcional e ficou de fora.

O que o projeto prova, sem um collector de verdade:

| Regra | Onde |
| :--- | :--- |
| Média de 120 ms com p99 em 2,57 s, e o quantil do balde em 3,0 s | `TelemetriaTest.mediaDe120msEscondeP99De2570ms` |
| Média dos p99 de 10 réplicas (790 ms) esconde o p99 real de 7 s | `TelemetriaTest.mediaDosP99EntreReplicasEscondeOCanary` |
| Pedido ainda em andamento não entra na média; o alerta de fila dispara | `TelemetriaTest.requisicoesAindaEmAndamentoNaoEntramNaMedia` |
| Pool com fila, CPU em 15% e zero HTTP 500 | `TelemetriaTest.poolSaturadoDisparaComCpuBaixaEZeroHttp500` |
| `order_id` e CPF recusados como label; 1.000 pedidos viram 2 séries | `TelemetriaTest.labelOrderIdOuCpfEProibidoENaoMultiplicaSerie` |
| Head de 1% descarta o trace lento; tail guarda erro e duração acima de 0,8 s | `TelemetriaTest.headDescartaOTraceLentoETailGuarda` |
| MDC não atravessa thread; o `traceparent` do evento atravessa | `TelemetriaTest.mdcNaoAtravessaThreadETraceparentAtravessa` |
| Log JSON sem CPF, com `trace_id` e `order_id`; trace-id só de zeros é rejeitado | `TelemetriaTest.traceparentRejeitaIdSoDeZerosELogNaoLevaCpf` |
| Chave `FONE-BF` ocupa 1 partição; `orderId` espalha | `TelemetriaTest.chaveSkuCaiNumaParticaoSoEOrderIdEspalha` |
| Sem saldo: `CANCELADO_SEM_COBRANCA`, zero cobrança, zero estorno, e um evento atrasado não confirma | `SagaDiagnosticoTest.semSaldoCancelaSemCobrancaENaoConfirma` |
| Estoque mudo e status desconhecido: não cobra, não cancela, não confirma | `SagaDiagnosticoTest.estoqueMudoComStatusDesconhecidoNaoCobraNemCancela` |
| Estoque mudo, consulta diz que não reservou: cancela sem cobrança | `SagaDiagnosticoTest.estoqueMudoMasConsultaMostraQueNaoReservouCancelaSemCobranca` |
| PSP mudo e status desconhecido: não estorna, não libera estoque, não cancela | `SagaDiagnosticoTest.pspMudoComStatusDesconhecidoNaoEstornaNemCancela` |
| PSP mudo, consulta diz recusado: cancela sem cobrança e libera o estoque uma vez | `SagaDiagnosticoTest.pspMudoMasConsultaMostraRecusadoCancelaSemCobrancaELiberaEstoque` |
| PSP mudo, consulta diz pago: segue e confirma | `SagaDiagnosticoTest.pspMudoMasConsultaMostraPagoEEstoqueConfirma` |
| Reserva expira: exatamente 1 estorno, mesmo com webhook e evento repetidos | `SagaDiagnosticoTest.reservaExpiraEstornaUmaVezMesmoComWebhookEEventoRepetidos` |
| Confirmação sem resposta: consulta o estoque antes de estornar | `SagaDiagnosticoTest.confirmacaoMudaConsultaAntesDeEstornar` |
| Confirmação desconhecida: não estorna e não marca `CONCLUIDO` | `SagaDiagnosticoTest.confirmacaoDesconhecidaNaoEstornaNemConfirma` |
| Estorno sem resposta: job vê "estornado" e não chama o PSP de novo | `SagaDiagnosticoTest.estornoSemRespostaFicaEmRevisaoEJobConcluiSemNovaChamada` |
| Job não estorna enquanto o status do estorno é desconhecido | `SagaDiagnosticoTest.jobNaoEstornaEnquantoStatusDoPspEDesconhecido` |
| Dentro de 24 h o job faz uma segunda chamada se o PSP ainda não estornou; não percorre as cinco esperas (1 s, 5 s, 30 s, 2 min, 10 min) | `SagaDiagnosticoTest.dentroDoPrazoJobTentaOEstornoUmaVez` |
| Depois de 24 h, `ESTORNO_FALHOU` e a consulta do dia seguinte ainda fecha | `SagaDiagnosticoTest.apos24hSemEstornoAbreAlertaENoDiaSeguinteAConsultaAindaFecha` |
| Teto de pendente bloqueia a segunda cobrança; a resposta não traz a soma nem CPF | `SagaDiagnosticoTest.tetoDePendenteBloqueiaNovaCobrancaERespostaNaoExpoeASoma` |
| Webhook com HMAC: assinatura errada não muda o estado; a certa confirma uma vez | `SagaDiagnosticoTest.webhookPagoConfirmaUmaVezEAssinaturaErradaNaoMudaNada` |
| Evento da saga usa `orderId` como chave e carrega o trace da entrada | `SagaDiagnosticoTest.eventosUsamOrderIdComoChaveECarregamOTrace` |
| A aplicação sobe, recusa CPF no corpo e recusa webhook sem assinatura | `SmokeApplicationTest` |

Os participantes, o teto e a idempotência do webhook estão em memória. Em produção isso mora no PostgreSQL, com `UNIQUE` no id do pagamento, como nas aulas anteriores. O histograma guarda as amostras individuais só para o teste mostrar o percentil exato (teto de 20.000). O número que o painel usa é o quantil calculado pelos baldes.

O teto deste projeto de teste é R$ 100 por cliente, para a segunda compra de R$ 80 estourar o limite numa execução curta. R$ 2.000 por cliente e R$ 8.000.000 no total, no `application.yml`, são fixture para o smoke subir. O teto de produção é o do system design: 1,5 vez a soma medida na hora anterior.

## Como rodar

```bash
mvn -B verify
```

## Resultado da validação (21/10/2026)

`mvn -B verify`: BUILD SUCCESS, 27 testes, 0 falhas (OpenJDK 21.0.12, Maven 3.9.9).

Não rodei o k6. O arquivo `k6/spike-telemetria.js` só mede a carga. A falha de 2% das chamadas de estoque dormindo 8 s é injetada pelos testes Java dos níveis 1 a 3.
