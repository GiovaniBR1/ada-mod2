# Anexo da Aula 6 — contas, PromQL, tabelas

O texto principal responde cada pergunta em poucas linhas. Aqui ficam as contas, as consultas e as tabelas. Os estados são os do [apêndice da Aula 4](../Aula%204/apendice-maquina-de-estados.md).

## Logs

Uma linha do pedido 48213 quando o PSP não responde:

```json
{"ts":"2026-11-27T21:04:12.318Z","level":"INFO","service":"checkout","trace_id":"0af7651916cd43dd8448eb211c80319c","span_id":"b7ad6b7169203331","order_id":"48213","estado":"PAGAMENTO_EM_VERIFICACAO","duracao_ms":2000,"cliente_ref":"9f3a12bc88e1","msg":"psp nao respondeu; consultando status antes de decidir"}
```

| Campo | Para que serve |
| :--- | :--- |
| `ts` | Ordenar o pedido e saber se caiu dentro do canary. |
| `level` | `INFO` ou `ERROR`. Em produção, 100% das linhas de erro e das mudanças de estado que não são sucesso, e amostra de 5% do caminho feliz. |
| `service` | `checkout`, `pagamento` ou `estoque`. |
| `trace_id` | A requisição inteira, igual nos três serviços. |
| `span_id` | Reservar e confirmar têm o mesmo `trace_id` e `span_id` diferentes. |
| `order_id` | O número que o chamado traz. O `trace_id` é opaco para quem abre o chamado. |
| `estado` | O passo da saga, nome do apêndice. |
| `duracao_ms` | 2.000 ms é uma das que o cliente sentiu; 70 ms é o caminho comum do exemplo da média. |
| `cliente_ref` | 12 hex. O CPF não entra na linha. |
| `msg` | Frase fixa quando a resposta não chega: "consultando status antes de decidir". O teste procura essa frase e o `trace_id` na mesma linha. |

Volume, decisão: a 5.000 pedidos/s, quatro linhas por pedido dariam 20.000 linhas/s. Com 5% do caminho feliz, cerca de 1.000 linhas/s, mais as linhas dos pedidos que travaram. O laboratório guarda todas as linhas na memória para o teste achar a frase.

| Estado | Dinheiro e estoque |
| :--- | :--- |
| `CONCLUIDO` | Reserva confirmada e pagamento capturado, os dois ao mesmo tempo. |
| `CANCELADO_SEM_COBRANCA` | Nada capturado: sem saldo, PSP recusou, ou teto de pendente estourou. |
| `CANCELADO_COM_ESTORNO` | Havia captura e o PSP confirmou o estorno. |
| `AGUARDANDO_PAGAMENTO` | Pix gerado e ainda não pago. O valor entra no teto de pendente. |
| `ESTOQUE_EM_VERIFICACAO` | Estoque mudo e a consulta não fechou. Não cobramos. |
| `PAGAMENTO_EM_VERIFICACAO` | PSP mudo e a consulta não fechou. Não estornamos e não liberamos a reserva. |
| `CONFIRMACAO_EM_VERIFICACAO` | Pagamento capturado e a confirmação do estoque não respondeu. Não estornamos enquanto o estoque for desconhecido. |
| `ESTORNO_EM_REVISAO` | As cinco tentativas acabaram sem confirmação do PSP. O chamado abre aqui. |
| `ESTORNO_FALHOU` | 24 h em revisão sem confirmação. O job consulta no dia seguinte e ainda pode fechar em `CANCELADO_COM_ESTORNO`, sem segunda devolução. |

O teste `apos24hSemEstornoAbreAlertaENoDiaSeguinteAConsultaAindaFecha` deixa o pedido 25 h em revisão, vê o alerta, e na consulta do dia seguinte, com o PSP respondendo "estornado", o pedido vai para `CANCELADO_COM_ESTORNO` sem uma segunda chamada de estorno. As 24 h não substituem as cinco tentativas: as tentativas abrem a revisão; o prazo impede que a revisão não tenha fim.

<a id="propagacao"></a>
## Propagação

Árvore correta. O Checkout é o pai. Estoque e Pagamento são filhos. O Estoque não chama o Pagamento.

```text
Checkout span b7ad6b7169203331
  filho Estoque     00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01
  filho Pagamento   00-0af7651916cd43dd8448eb211c80319c-a1b2c3d4e5f67890-01
```

O `traceparent` que cada chamada leva tem o span-id daquele filho (`TraceContext.filho()`). Os dois compartilham o trace-id. O teste `estoqueMudoComStatusDesconhecidoNaoCobraNemCancela` confere que o trace-id que chegou no Estoque é o do pedido.

O HTTP de finalizar pode responder 202 rápido e o resto seguir num consumidor, em outra thread. O teste `mdcNaoAtravessaThreadETraceparentAtravessa` mostra as duas metades: a thread nova lê o MDC vazio; o evento leva o `traceparent`, o consumidor lê o cabeçalho e o `trace_id` é o mesmo.

A chave de partição do Kafka é o `orderId`. O Kafka só garante ordem dentro de uma partição, e a mesma chave cai sempre na mesma partição. Assim, reservar, pagar e confirmar do pedido 48213 são lidos em ordem. A chave `skuId` faria o contrário: os pedidos do produto `FONE-BF` cairiam todos na mesma partição. O teste `chaveSkuCaiNumaParticaoSoEOrderIdEspalha` usa 12 partições (decisão do projeto): `FONE-BF` ocupa 1, e 2.000 `orderId` diferentes ocupam mais de 6. `clienteId` juntaria pedidos diferentes da mesma pessoa. `traceId` separaria os eventos do mesmo pedido se uma retentativa abrisse outro trace.

Transição condicional: `UPDATE ... WHERE estado = 'AGUARDANDO_PAGAMENTO'`. `PagamentoConfirmado` depois de `CANCELADO_SEM_COBRANCA`, com o dinheiro capturado, leva o pedido a `ESTORNANDO`, como no apêndice da Aula 4. O teste `semSaldoCancelaSemCobrancaENaoConfirma` é o outro caso: nada foi capturado, o estado continua `CANCELADO_SEM_COBRANCA` e o contador de cobranças continua em zero.

Webhook do PSP: o parceiro não conhece o nosso trace. Na criação do pagamento gravamos `pagamentoId = "pag-" + orderId` e o `trace_id` no pedido. O webhook manda `pag-48213|48213|PAGO`, achamos o pedido e a linha nova reusa o `trace_id` original. A assinatura é HMAC-SHA256 do corpo exato, no cabeçalho `X-Assinatura`. Assinatura errada responde 401 e não muda o estado. O mesmo `pagamentoId` na segunda vez responde "repetido" e não confirma o estoque de novo (`webhookPagoConfirmaUmaVezEAssinaturaErradaNaoMudaNada`).

## LGPD

| Dado | Tratamento | Para que ainda serve |
| :--- | :--- | :--- |
| `order_id` | Na linha, acesso restrito, prazo curto. | Número do chamado. Continua dado pessoal. |
| `cliente_ref` | HMAC-SHA256 do CPF, chave fora do log, 12 hex. | Agrupar pedidos da mesma pessoa. O teste usa `39053344705` e confere que a linha não contém esses dígitos. |
| Valor, estado, duração, serviço, versão, código HTTP | Como estão. | Não identificam a pessoa. |
| "2% da v2 passou de 1 s" | Agregado, sem identificador. | Dado anonimizado. |

O art. 12 diz que o dado anonimizado deixa de ser dado pessoal, salvo se o processo puder ser revertido com esforço razoável. O art. 13, §4º, define pseudonimização no artigo de pesquisa em saúde; a técnica é a mesma do `cliente_ref`: a associação só volta com a chave, guardada à parte, em ambiente controlado. Enquanto a chave existir, o ref pode ser revertido.

## RED

Baldes do histograma, em segundos: 0,05, 0,1, 0,25, 0,5, 1, 2, 4, 8 e +Inf. O painel usa o balde. O percentil exato dos testes usa as amostras só dentro do teste, com teto de 20.000.

| Sinal | O que conta | Nome |
| :--- | :--- | :--- |
| Rate | Chamadas por segundo | `rate(checkout_operacao_duracao_seconds_count[1m])` |
| Errors | Timeout, desistência, estado de verificação, HTTP 5xx | a mesma série, com `resultado` em `verificacao`, `estorno_falhou` ou `sem_cobranca` |
| Duration | p50, p99, fração acima de 1 s, por versão | `histogram_quantile` sobre `checkout_operacao_duracao_seconds_bucket` |

Conta da média de 120 ms, reproduzida por `mediaDe120msEscondeP99De2570ms`:

- 9.800 × 0,07 + 200 × 2,57 = 686 + 514 = 1.200
- Média = 1.200 / 10.000 = 0,12 s
- p50 = 0,07 s
- p99 exato = 2,57 s (a 9.900ª chamada das 10.000 ordenadas já está no grupo lento)
- Quantil do balde (2 s, 4 s]: 2 + (4 − 2) × (9.900 − 9.800) / 200 = 3,0 s

O balde erra o valor (3,0 s no lugar de 2,57 s) e os dois estão muito acima de 120 ms.

Lei de Little, teste `requisicoesAindaEmAndamentoNaoEntramNaMedia`. Os 8 s são estimativa. O limiar do alerta é o dobro da base medida em 10 min, não os 8 s: se o timeout do aplicativo for outro, a conta do exemplo muda e o limiar não.

- Base: 4.900 × 0,120 s = 588
- Cauda: 100 × 8 s = 800
- Total = 1.388; dobro da base = 1.176; 1.388 dispara, 588 não
- Medidores: `checkout_http_em_andamento` e `checkout_pedidos_em_aberto`

Canary, teste `mediaDosP99EntreReplicasEscondeOCanary`:

- 9 réplicas estáveis, 1.000 chamadas cada, todas a 100 ms. p99 de cada uma = 100 ms.
- 1 réplica do canary: 800 a 100 ms e 200 a 7 s. p99 dessa réplica = 7 s.
- Média desses dez p99 = (9 × 100 + 7.000) / 10 = 790 ms. Alerta "média dos p99 acima de 800 ms" fica em silêncio.
- No conjunto: 9.800 a 100 ms e 200 a 7 s. p99 verdadeiro = 7 s. Balde de 4 s a 8 s estima 6,0 s: 4 + (8 − 4) × 100 / 200. 6 s dispara o alerta de 800 ms.
- Média geral = (9.800 × 0,1 + 200 × 7) / 10.000 = 238 ms. Alerta de média acima de 300 ms também fica em silêncio.

```promql
histogram_quantile(0.99, sum by (le, versao) (rate(checkout_operacao_duracao_seconds_bucket[1m])))
```

`sum by (le, versao)` junta as réplicas da mesma versão e mantém v1 e v2 separadas. Somar os baldes primeiro é o que faz o 6,0 s aparecer. Tirar o p99 em cada réplica e depois fazer a média é a conta dos 790 ms.

| Alerta | Dispara neste desenho | Fica em silêncio de propósito |
| :--- | :--- | :--- |
| p99 de `finalizar` acima de 800 ms por 2 min, com pelo menos 100 chamadas na janela | 3,0 s e também 6,0 s | |
| `checkout_http_em_andamento` acima do dobro da base de 10 min, por 1 min | 1.388 contra base 588 | |
| `checkout_pedidos_em_aberto` ou verificação com mais de 5 min | saga parada depois do HTTP 202 | 5 min é decisão. O teste chama `reconciliar` direto |
| conversão abaixo de 0,97 por 3 min | a queda que o chamado descreveu | 0,97 é decisão; a aula não publica a conversão normal |
| média acima de 300 ms | | 120 ms e 238 ms |
| CPU acima de 80% como único sinal | | a CPU do incidente estava abaixo de 40% |

## USE

| Recurso | Utilização | Saturação, antes do 500 | Erros |
| :--- | :--- | :--- | :--- |
| Pool do PostgreSQL (HikariCP) | conexões em uso / tamanho | threads esperando conexão (`fila > 0`) | timeout ao pegar conexão |
| Pool HTTP do PSP e do Estoque | chamadas em uso / limite | chamadas esperando uma vaga | timeout de conexão |
| Consumidor da saga no Kafka | registros em processamento / paralelismo | lag em mensagens e em segundos | falha ao processar, indo para retry |
| Pix ainda não confirmado | soma em `AGUARDANDO_PAGAMENTO` / teto medido | pedido novo recusado pelo teto, HTTP 200 e `CANCELADO_SEM_COBRANCA` | `ESTORNO_FALHOU` (24 h depois das cinco tentativas, com consulta diária) |

`poolSaturadoDisparaComCpuBaixaEZeroHttp500`: pool 10/10, fila 200, CPU 15%, HTTP 500 = 0, alerta ligado. Com fila zero, o mesmo alerta fica em silêncio.

O teto em reais não é chutado. A aula não diz a fração de Pix nem o ticket. A conta 20% × 5.000 TPS × 30 s × R$ 180 = R$ 5.400.000 só mostra a forma (vazão × fração × tempo aberto × ticket). Os insumos não estão no enunciado e não entram no alerta. Decisão: o teto global é 1,5 vez a soma medida de Pix em `AGUARDANDO_PAGAMENTO` na hora anterior à abertura. O teto por cliente é o maior valor em aberto que o negócio já aceitou, também medido. Enquanto essa base não existe, o sinal é a contagem de pedidos em `AGUARDANDO_PAGAMENTO` e em estado de verificação. O teste usa um teto pequeno de propósito (R$ 100 por cliente e dois pedidos de R$ 80). Os números do `application.yml` (R$ 2.000 e R$ 8.000.000) são fixture para o laboratório subir.

## Labels

| Label proibido | Efeito no servidor de métricas |
| :--- | :--- |
| `order_id` | 5.000/s × 60 s = 300.000 ids num minuto × 11 séries = 3,3 milhões de séries por minuto. O teste recusa com exceção. |
| `trace_id` | Um valor por requisição. A mesma explosão. O trace já guarda esse id. |
| `user_id` | Uma série por cliente. No pico o conjunto cresce o tempo todo, e o id da pessoa iria para o banco de métricas. |
| `cpf` e `email` | O mesmo crescimento, e ainda é dado pessoal dentro do TSDB (o banco de séries do Prometheus). |
| `sku_id` | O catálogo tem milhares de produtos. Um produto quente não torna o label seguro. |

## Traces

Premissas marcadas como estimativa: 6 spans por pedido (entrada, checkout, reservar, cobrar, confirmar, evento) e cerca de 1 KB por span.

- Guardar 100% até o backend: 5.000 × 6 KB = 30 MB/s, cerca de 2,6 TB/dia antes de réplica de disco.
- Com 2% travados (100 pedidos/s) guardados por inteiro e 1% do restante (49/s), seguem cerca de 150 traces/s, perto de 3% do volume, cerca de 80 GB/dia.
- `decision_wait` = 15 s, maior que o maior timeout da cadeia (8 s no Estoque e no aplicativo; 5 s no Pagamento). Traces em voo = 5.000 × 15 = 75.000. Memória ≈ 75.000 × 6 KB = 450 MB (estimativa). Pedido de memória do collector: 2 GB (decisão), com alerta de fila. Se a fila encher, o tail degrada, e o alerta existe para ver isso antes de perder o trace lento.
- Prazo depois de aceito: 7 dias (decisão, seção de LGPD).

O trace-id do inteiro 100, formatado em 32 hex, cai fora do corte de 1% (resto da divisão por 10.000 é 100; o corte é "menor que 100"). Com a decisão no fim, 2,57 s é maior que 0,8 s e o trace é guardado. O mesmo trace-id com 0,07 s e sem erro é descartado. Com erro, é guardado mesmo durando 0,07 s. O teste `headDescartaOTraceLentoETailGuarda` faz essas três conferências. O inteiro 50 cai dentro do 1% e seria guardado mesmo sendo rápido.

## Testes

| Serviço | O que falta hoje | O que passa a expor |
| :--- | :--- | :--- |
| Checkout | média sem histograma, canary sem série por versão, nenhum estado no log | `trace_id`, `order_id`, `estado`, histograma por `versao`, chamadas em andamento, conversão |
| Pagamento | a chamada ao PSP fora do trace; timeout vira silêncio | o mesmo `traceparent`, span filho do Checkout na chamada ao PSP, `estado` `PAGAMENTO_EM_VERIFICACAO` ou `ESTORNO_EM_REVISAO`, duração de `cobrar` e de `estornar` |
| Estoque | reserva sem span quando a resposta não volta | o mesmo `traceparent`, span filho do Checkout em reservar e em confirmar, `estado` `ESTOQUE_EM_VERIFICACAO` ou `CONFIRMACAO_EM_VERIFICACAO` |

Mitigação enquanto a causa é desconhecida, as duas reversíveis:

1. Abortar o canary e devolver 100% à frota estável (o abort da Aula 5, pré-aprovado). Sem desfazer migração e sem reiniciar a frota inteira.
2. Enquanto `checkout_http_em_andamento` ou a contagem de verificação estiver acima do limiar, o Checkout responde 503 com `Retry-After` nos `finalizar` novos. O aplicativo repete com a mesma chave de idempotência. Pedido que já está em verificação não é cancelado e não é estornado.

| Hipótese | Sinal que confirma | Sinal que descarta |
| :--- | :--- | :--- |
| PSP lento ou mudo | p99 ou em andamento de `cobrar` alto; trace parado no span do PSP; sobe `PAGAMENTO_EM_VERIFICACAO`; CPU baixa | span do PSP curto; o estado parado é estoque ou confirmação; o lag sobe com o PSP saudável |
| Pool do banco cheio | fila > 0, utilização 100%, CPU baixa, HTTP 500 ainda zero | fila em zero enquanto as chamadas em andamento sobem |
| Consumidor da saga parado | HTTP 202 perto de 120 ms, `checkout_pedidos_em_aberto` sobe, lag sobe | lag estável e o atraso está no span HTTP, antes do 202 |
| Réplica do canary (`v2`) | p99 da `versao=v2` alto e p99 da `v1` estável. A média dos p99 por réplica pode ficar em 790 ms | `v1` e `v2` com o mesmo p99 |
| Estoque mudo | sobe `ESTOQUE_EM_VERIFICACAO`; span do estoque longo; esses traces têm zero cobranças | o estado parado é pagamento ou confirmação |

Postmortem, formato blameless do SRE book. Sintoma: tela carregando e conversão caindo. Fatores do sistema: log sem id compartilhado, duração publicada como média, saturação de pool e lag fora do painel, conversão fora do canary, e um estado que não dizia se o dinheiro tinha se movido. Quem olhou o painel viu verde porque o painel só tinha média, CPU e HTTP 500. A causa fica na linha da tabela que os sinais fecharem.

| Teste | O que tem de ser verdade | O que faz falhar |
| :--- | :--- | :--- |
| `mediaDe120msEscondeP99De2570ms` | média 120 ms, p99 2,57 s, balde 3,0 s, alerta de 800 ms ligado | alerta de média acima de 300 ms ligado |
| `mediaDosP99EntreReplicasEscondeOCanary` | média dos p99 = 790 ms em silêncio; quantil somado = 6,0 s disparado; média geral 238 ms | tratar 790 ms como o p99 do conjunto |
| `requisicoesAindaEmAndamentoNaoEntramNaMedia` | 1.388 dispara; a base 588 não; a média das concluídas continua 120 ms | |
| `poolSaturadoDisparaComCpuBaixaEZeroHttp500` | fila 200, pool 10/10, CPU 15%, HTTP 500 = 0 | o mesmo alerta com fila zero |
| `labelOrderIdOuCpfEProibidoENaoMultiplicaSerie` | 1.000 pedidos geram 2 séries | `order_id`, `cpf` ou CPF dentro de um label permitido |
| `headDescartaOTraceLentoETailGuarda` | 2,57 s guardado no tail; 0,07 s sem erro descartado; erro guardado | |
| `mdcNaoAtravessaThreadETraceparentAtravessa` | thread nova vê o mapa vazio; o evento devolve o mesmo trace-id | |
| `traceparentRejeitaIdSoDeZerosELogNaoLevaCpf` | cabeçalho só de zeros rejeitado; a linha tem `trace_id` e `order_id` e não tem o CPF | |

| Situação no `SagaDiagnosticoTest` | Estado | Verificação negativa |
| :--- | :--- | :--- |
| Estoque sem saldo | `CANCELADO_SEM_COBRANCA` | nada foi capturado: zero cobranças, zero estornos, e um `PagamentoConfirmado` atrasado não muda o estado |
| Estoque mudo e consulta desconhecida | `ESTOQUE_EM_VERIFICACAO` | zero cobranças e zero estornos |
| Consulta diz que não reservou | `CANCELADO_SEM_COBRANCA` | zero cobranças; não há liberação, porque não houve reserva |
| PSP mudo e consulta desconhecida | `PAGAMENTO_EM_VERIFICACAO` | zero estornos e zero liberações; a resposta não traz a soma pendente nem CPF |
| Consulta do PSP diz recusado | `CANCELADO_SEM_COBRANCA` | exatamente uma liberação e zero estornos |
| Consulta do PSP diz pago | `CONCLUIDO` | zero estornos, e a segunda chamada do mesmo `orderId` não cobra de novo |
| Reserva expira depois do pagamento | `CANCELADO_COM_ESTORNO` | exatamente um estorno, mesmo com webhook e evento repetidos |
| Confirmação muda e a consulta diz que a reserva expirou | `CANCELADO_COM_ESTORNO` | a consulta aconteceu antes do estorno |
| Confirmação desconhecida | `CONFIRMACAO_EM_VERIFICACAO` | zero estornos e o estado não é `CONCLUIDO`, embora o dinheiro já tenha sido capturado |
| Estorno mudo; o job depois vê "estornado" | `CANCELADO_COM_ESTORNO` | nenhuma chamada nova de estorno |
| Job consulta e o estorno segue desconhecido | `ESTORNO_EM_REVISAO` | nenhuma chamada nova de estorno |
| Dentro de 24 h o PSP diz que ainda não estornou | uma retentativa, e `CANCELADO_COM_ESTORNO` se ela voltar "estornado" | a retentativa é uma chamada, não duas |
| 24 h sem confirmação | `ESTORNO_FALHOU` e um alerta; no dia seguinte a consulta ainda pode fechar | nenhuma chamada nova de estorno nesse fechamento |
| Segundo pedido estoura o teto | `CANCELADO_SEM_COBRANCA` | zero cobranças para esse pedido, estoque liberado uma vez |
| Webhook com assinatura errada | o estado não muda | nenhuma confirmação de estoque |
| Webhook `PAGO` com HMAC certo, e o mesmo de novo | `CONCLUIDO` na primeira | a segunda não confirma o estoque outra vez |

Spike k6 (`desafio/k6/spike-telemetria.js`):

| Fase | Duração | Chegadas |
| :--- | :--- | :--- |
| Base | 1 min | 500/s |
| Subida | 10 s | até 5.000/s |
| Pico | 1 min | 5.000/s |
| Descida | 10 s | de volta a 500/s |
| Recuperação | 1 min | 500/s |

No pico, 2% das chamadas de estoque dormem 8 s. Passa só se, em até 30 s, o alerta de quantil ou o de em andamento estiver disparado, o de média acima de 300 ms estiver em silêncio, um trace lento tiver o mesmo `trace_id` no Checkout e nos dois spans filhos (Estoque e Pagamento), a saída de métricas não tiver `order_id`, `user_id` nem `cpf`, e na recuperação `checkout_http_em_andamento` voltar para perto da base. Pedido no meio fica em verificação, nunca em `CONCLUIDO`, e o job só o move depois de consultar o status.
