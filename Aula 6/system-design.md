# Template de System Design: Aula 06 - Observabilidade & Diagnóstico em Produção

> **Contexto do Cenário: O Checkout que Trava sem Dar Erro**
>
> Sua equipe responde pelo serviço de **Checkout** do marketplace, que chama **Pagamento** e **Estoque**. Os três rodam em imagens *Distroless*.
>
> 1. **O Incidente:** Na abertura da Black Friday, com **5.000 TPS**, uma versão nova do Checkout entrou em *Canary*. Minutos depois, os painéis continuavam verdes, com latência média de 120 ms, CPU abaixo de 40% e nenhum pico de HTTP 500.
> 2. **O Impacto:** A conversão despencou. Milhares de reclamações chegaram ao suporte, todas com a mesma cena. O cliente clica em "Finalizar Pedido", a tela fica carregando e o aplicativo desiste por timeout.
> 3. **As Restrições:** A imagem não tem `sh` e não há SSH nos servidores. Cada serviço escreve logs em texto livre, do seu jeito, sem um identificador que ligue a chamada do Checkout à chamada que ele fez ao Pagamento. E dado de cliente que vai parar em log continua sujeito à LGPD.
>
> **Instruções:** Responda às decisões arquiteturais abaixo fundamentando suas escolhas estritamente com base nos requisitos e restrições deste cenário. Documente suas decisões no repositório contínuo do seu projeto através de commits atômicos e bem descritivos.

---

## 1. Logs e Correlação

- **Campos do Log:** Que campos todo log do Checkout precisa ter para você encontrar, no meio de milhares de linhas, as da requisição que travou? Liste os campos e diga para que serve cada um.
  > O suporte chega com um número de pedido, por exemplo o **48213**, e com a frase "a tela ficou carregando". A busca começa por esse número e termina no serviço em que a chamada parou. Para isso, toda linha é um JSON único, igual nos três serviços. Uma linha do pedido 48213, no momento em que o Pagamento não respondeu, fica assim (o `trace_id` abaixo é o exemplo da especificação W3C, para dar para ler o formato):
  >
  > ```json
  > {"ts":"2026-11-27T21:04:12.318Z","level":"INFO","service":"checkout","trace_id":"0af7651916cd43dd8448eb211c80319c","span_id":"b7ad6b7169203331","order_id":"48213","estado":"PAGAMENTO_EM_VERIFICACAO","duracao_ms":2000,"cliente_ref":"9f3a12bc88e1","msg":"psp nao respondeu; consultando status antes de decidir"}
  > ```
  >
  > A busca, na ordem em que ela acontece:
  >
  > 1. `order_id="48213"` devolve as linhas deste pedido.
  > 2. A linha traz o `trace_id`. A mesma busca, agora pelo `trace_id`, devolve a linha do Checkout, a do Pagamento e a do Estoque. É a mesma requisição nos três serviços.
  > 3. O `estado` diz onde ela parou. `PAGAMENTO_EM_VERIFICACAO` significa: chamamos o PSP, a resposta não chegou, consultamos o status e ele continua desconhecido. O pedido não foi cancelado e o dinheiro não foi estornado.
  >
  > Cada campo existe por uma pergunta concreta:
  >
  > | Campo | A pergunta que ele responde |
  > | :--- | :--- |
  > | `ts` | Em que instante a linha foi escrita, para ordenar o pedido e saber se caiu dentro do canary. |
  > | `level` | `INFO` ou `ERROR`. Em produção guardamos 100% das linhas de erro e das mudanças de estado que não são sucesso, e uma amostra de 5% do caminho feliz. A 5.000 pedidos/s, quatro linhas por pedido dariam 20.000 linhas/s. Com 5% do caminho feliz são cerca de 1.000 linhas/s, mais as linhas dos pedidos que travaram. **Decisão** de volume; o projeto desta aula guarda todas as linhas na memória para o teste poder achar a frase. |
  > | `service` | Quem escreveu: `checkout`, `pagamento` ou `estoque`. Sem isso, um log misturado não diz em qual serviço a chamada parou. |
  > | `trace_id` | O identificador da requisição inteira, 32 caracteres hexadecimais. Igual nos três serviços. É o elo que o texto livre de hoje não tem. |
  > | `span_id` | O pedaço desta requisição dentro deste serviço, 16 hex. Reservar estoque e confirmar estoque têm o mesmo `trace_id` e `span_id` diferentes. Dá para ver qual dos dois demorou os 2.000 ms. |
  > | `order_id` | O número que o cliente e o suporte conhecem. O `trace_id` é ótimo para a máquina e opaco para a pessoa que abre o chamado. Os dois ficam na mesma linha. |
  > | `estado` | Em que passo o pedido está. Os valores e o motivo de serem separados estão na tabela abaixo. |
  > | `duracao_ms` | Quanto esta etapa levou. Uma linha com 2.000 ms é uma das que o cliente sentiu. Uma linha com 70 ms é o caminho comum do exemplo da seção 2. |
  > | `cliente_ref` | Um apelido do cliente, 12 hex, para agrupar os pedidos da mesma pessoa. O CPF não entra na linha. A seção de LGPD diz como esse apelido é calculado e quem pode revertê-lo. |
  > | `msg` | A frase curta do que aconteceu. Quando a resposta não chega, a frase é fixa: "consultando status antes de decidir". O teste procura essa frase e o `trace_id` na mesma linha. |
  >
  > O `estado` do log é o estado do pedido do [apêndice da Aula 4](../Aula%204/apendice-maquina-de-estados.md). Um único `CANCELADO` esconderia se o dinheiro se moveu, e foi por isso que ele saiu de cena. Não há uma terceira máquina: `AGUARDANDO_PIX` e `CONFIRMADO`, que o laboratório ainda grava, são sinônimos de `AGUARDANDO_PAGAMENTO` e `CONCLUIDO`.
  >
  > | Estado | O que aconteceu com o dinheiro e com o estoque |
  > | :--- | :--- |
  > | `CONCLUIDO` | Estoque reservado e pagamento capturado. Os dois precisam ser verdade ao mesmo tempo. No laboratório o assert usa `CONFIRMADO`. |
  > | `CANCELADO_SEM_COBRANCA` | Nada foi capturado. Acontece quando não há saldo, quando o PSP recusa, ou quando o teto de pendente estoura. |
  > | `CANCELADO_COM_ESTORNO` | O pagamento tinha sido capturado e o PSP confirmou o estorno. |
  > | `AGUARDANDO_PAGAMENTO` | O Pix foi gerado e ainda não foi pago. O valor entra no teto de pendente. No laboratório o assert usa `AGUARDANDO_PIX`. |
  > | `ESTOQUE_EM_VERIFICACAO` | O Estoque não respondeu e a consulta do status também não fechou. Não cobramos. |
  > | `PAGAMENTO_EM_VERIFICACAO` | O PSP não respondeu e a consulta não fechou. Não estornamos e não liberamos o estoque, porque a cobrança pode ter acontecido. |
  > | `CONFIRMACAO_EM_VERIFICACAO` | O pagamento já foi capturado e a confirmação do estoque não respondeu. Não estornamos enquanto o status do estoque for desconhecido. |
  > | `ESTORNO_EM_REVISAO` | As cinco tentativas da Aula 4 acabaram sem confirmação do PSP. O chamado abre aqui. |
  > | `ESTORNO_FALHOU` | 24 h em `ESTORNO_EM_REVISAO` sem confirmação. O chamado continua. O job consulta o PSP no dia seguinte e ainda pode fechar em `CANCELADO_COM_ESTORNO`, sem segunda devolução. As 24 h não substituem as cinco tentativas: elas abrem a revisão; o prazo impede que a revisão não tenha fim. |
  >
  > Esses estados de revisão têm saída automática. Um job consulta o status real no participante (o PSP para pagamento e estorno, o Estoque para a reserva) e só então muda o estado. "A equipe vai olhar" não é saída: no pico, a equipe está apagando o incidente. O teste `apos24hSemEstornoAbreAlertaENoDiaSeguinteAConsultaAindaFecha` deixa o pedido 25 h em revisão, vê o alerta, e na consulta do dia seguinte, com o PSP respondendo "estornado", o pedido vai para `CANCELADO_COM_ESTORNO` sem uma segunda chamada de estorno.
  >
  > A imagem Distroless não tem `sh` e o servidor não tem SSH. A linha acima é o que substitui entrar na máquina. Um container de debug (`kubectl debug` com uma imagem que tenha shell, ao lado do processo) serve para um thread dump ao vivo: dá para ver threads paradas em leitura de socket, com a CPU baixa. Ele não reconstrói o pedido 48213 no Pagamento e no Estoque. Isso só a linha com `trace_id` e `order_id` faz.
- **O Caminho do Contexto:** Como o mesmo identificador de rastreamento passa do Checkout para o Pagamento e o Estoque numa chamada HTTP?
  > O identificador viaja num cabeçalho HTTP padrão, o `traceparent` da W3C Trace Context. Ele tem quatro pedaços separados por hífen:
  >
  > ```text
  > traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
  >                │  └─ trace-id, 32 hex, a requisição inteira
  >                │     └─ span-id, 16 hex, este salto
  >                └─ versão 00                          └─ 01 = este trace está marcado para ser guardado
  > ```
  >
  > `00` é a versão do formato. O trace-id e o span-id feitos só de zeros são inválidos e o código rejeita esse cabeçalho (`traceparentRejeitaIdSoDeZerosELogNaoLevaCpf`). O último par de dígitos é a flag: `01` significa que a decisão foi guardar este trace.
  >
  > O caminho do pedido 48213, passo a passo:
  >
  > 1. O aplicativo chama `POST /checkout/finalizar`. Se ele já mandou um `traceparent` válido, o Checkout continua aquele trace. Se não mandou, o Checkout cria um trace novo.
  > 2. O Checkout abre o seu span, grava `trace_id` e `span_id` na linha de log e chama o Estoque com um filho: o mesmo trace-id e um span-id novo.
  >
  >     ```text
  >     Checkout  ->  Estoque
  >     00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
  >     00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01
  >     ```
  >
  > 3. O Estoque lê o cabeçalho, escreve o mesmo `trace_id` nas linhas dele e faz o mesmo na hora de chamar o Pagamento, de novo com um span-id novo.
  > 4. Quem procura `0af7651916cd43dd8448eb211c80319c` vê os três serviços em ordem.
  >
  > O projeto faz isso em `TraceContext`: `header()` monta a string, `filho()` troca só o span-id, e o Estoque de teste guarda o cabeçalho que recebeu. O teste `estoqueMudoComStatusDesconhecidoNaoCobraNemCancela` confere que o trace-id que chegou no Estoque é o do pedido.
  >
  > Tem um segundo caminho, e ele é o que explica a tela carregando com a média em 120 ms. O HTTP de "finalizar" pode responder `202` rápido (Pix gerado, ou evento da saga publicado) e o resto do pedido seguir num consumidor, em outra thread. O mapa de contexto da thread (o MDC do SLF4J, um `ThreadLocal`) não atravessa essa troca: a thread nova acorda com o mapa vazio. O teste `mdcNaoAtravessaThreadETraceparentAtravessa` mostra as duas metades. Na primeira, uma thread nova lê o mapa e encontra vazio. Na segunda, o evento leva o `traceparent` no cabeçalho da mensagem, o consumidor lê esse cabeçalho e o `trace_id` é o mesmo.
  >
  > O evento também precisa de uma chave de partição, e a chave é o `orderId`. O Kafka só garante a ordem dentro de uma partição, e a mesma chave cai sempre na mesma partição. Assim, "reservar", "pagar" e "confirmar" do pedido 48213 são lidos em ordem, por um consumidor de cada vez. A chave `skuId` faria o contrário na Black Friday: os 2.000 pedidos do produto `FONE-BF` cairiam todos na mesma partição, e as outras 11 ficariam ociosas. O teste `chaveSkuCaiNumaParticaoSoEOrderIdEspalha` usa 12 partições (**decisão** do projeto) e mostra exatamente isso: `FONE-BF` ocupa 1 partição, e 2.000 `orderId` diferentes ocupam mais de 6. `clienteId` juntaria pedidos diferentes da mesma pessoa e ainda concentraria um comprador grande. `traceId` separaria os eventos do mesmo pedido se uma retentativa abrisse outro trace.
  >
  > Um evento atrasado ou repetido não desfaz o estado. A mudança só ocorre se o estado atual ainda é o esperado (`UPDATE ... WHERE estado = 'AGUARDANDO_PAGAMENTO'`; no laboratório o valor gravado ainda é `AGUARDANDO_PIX`). O teste `semSaldoCancelaSemCobrancaENaoConfirma` manda `PagamentoConfirmado` depois do cancelamento sem cobrança: o estado continua `CANCELADO_SEM_COBRANCA` e o contador de cobranças continua em zero.
  >
  > O webhook do PSP é o caso em que o identificador chega de fora e não traz o nosso `traceparent`. O PSP não conhece o nosso trace. Na hora de criar o pagamento gravamos `pagamentoId = "pag-" + orderId` e o `trace_id` no pedido. O webhook manda `pag-48213|48213|PAGO`, nós achamos o pedido e a linha nova reusa o `trace_id` original. A assinatura é HMAC-SHA256 do corpo exato, no cabeçalho `X-Assinatura`. Assinatura errada responde 401 e não muda o estado. O mesmo `pagamentoId` na segunda vez responde "repetido" e não confirma o estoque de novo. Os dois casos estão em `webhookPagoConfirmaUmaVezEAssinaturaErradaNaoMudaNada`.
- **LGPD nos Logs:** Quais dados de cliente ficam fora de logs e traces? Para os identificadores que precisam aparecer, qual tratamento você aplica e quem pode ler esses registros?
  > Ficam fora de logs, de traces e de labels de métrica: CPF, nome, e-mail, telefone, endereço completo, número do cartão, CVV e o IP completo. O corpo de `POST /checkout/finalizar` que trouxer `cpf`, `email` ou `cartao` recebe HTTP 400, e o smoke test cobre o CPF. A resposta de um pagamento ainda pendente leva o valor daquele pedido e o estado. Ela não leva a soma de tudo que aquele cliente tem pendente, nem o cartão. O teste `tetoDePendenteBloqueiaNovaCobrancaERespostaNaoExpoeASoma` procura essas chaves e não encontra.
  >
  > O que precisa aparecer, e como:
  >
  > | Dado | Tratamento | Por que ainda dá para operar |
  > | :--- | :--- | :--- |
  > | `order_id` | Vai na linha, como está. Acesso restrito e prazo curto. | É o número do chamado. Sozinho, na ferramenta de log, ele aponta para uma pessoa, então continua sendo dado pessoal. |
  > | `cliente_ref` | HMAC-SHA256 do CPF com uma chave que não está no log. A linha guarda os 12 primeiros hex. O mesmo CPF gera sempre o mesmo ref. | Dá para agrupar os pedidos da mesma pessoa. O teste usa o CPF `39053344705` e confere que a linha não contém esses dígitos. |
  > | Valor, estado, duração, serviço, versão, código HTTP | Vão como estão. Não identificam a pessoa. | São o que o painel e a linha precisam para dizer se a chamada travou. |
  > | "2% das chamadas da v2 passaram de 1 s" | Número agregado, sem identificador. | É o dado anonimizado da seção de métricas. |
  >
  > A diferença entre os dois tratamentos está na Lei 13.709/2018:
  >
  > - **Anonimizar** (art. 5º, XI) é aplicar meios técnicos razoáveis para o dado perder a possibilidade de associação, direta ou indireta, a uma pessoa. O art. 12 diz que o dado anonimizado não é dado pessoal, salvo se o processo puder ser revertido com esforço razoável. O agregado "2% da v2 passou de 1 s" está nesse grupo: não há caminho de volta até o pedido 48213.
  > - **Pseudonimizar**, na definição do art. 13, §4º, é o tratamento em que o dado perde a associação direta, e a associação só volta com uma informação adicional guardada à parte, pelo controlador, em ambiente controlado. O parágrafo define o termo para o artigo de pesquisa em saúde. A técnica é a mesma que usamos no `cliente_ref`: a linha não tem o CPF, e a chave do HMAC fica no cofre. Enquanto a chave existir, o ref pode ser revertido, então ele continua dado pessoal. Pseudonimizar não é o mesmo que anonimizar.
  >
  > Quem lê o quê:
  >
  > - O painel agregado, sem `order_id` e sem `cliente_ref`, fica com o time de engenharia. É o painel da seção 2.
  > - A linha com `order_id` e `cliente_ref` fica com o plantão e com o suporte. O acesso é com login, e a consulta fica registrada.
  > - A chave do HMAC não fica com quem lê a linha. Ela fica no cofre. Uma ação separada, "identificar o cliente deste pedido", usa a chave, e essa ação também fica registrada. Quem está no meio do incidente vê `9f3a12bc88e1` e não reconstrói o CPF a partir da linha.
  >
  > Prazo de guarda, pelo princípio da necessidade (art. 6º, III: o mínimo para a finalidade). A lei não fixa estes dias. São **decisão** desta entrega: trace por 7 dias, log com `order_id` por 15 dias, métrica agregada por 30 dias. A finalidade é diagnosticar o incidente e atender o chamado, não montar um histórico do cliente.

---

## 2. Métricas

- **Visão do Cliente (RED):** Quais métricas do Checkout entram no painel? Explique como você mede a duração para que o travamento de uma parte dos clientes não desapareça na média.
  > RED, do método do Tom Wilkie (Grafana), é a visão de quem está do lado de fora do serviço. Para cada operação (`finalizar`, `reservar`, `cobrar`, `estornar`, `confirmar`) em cada serviço, o painel tem três números:
  >
  > | Sinal | O que conta no Checkout | Nome no projeto |
  > | :--- | :--- | :--- |
  > | Rate, a vazão | Chamadas por segundo | `rate(checkout_operacao_duracao_seconds_count[1m])` |
  > | Errors, os erros | Timeout, desistência do aplicativo, estado de verificação e HTTP 5xx. HTTP 500 sozinho ficou em zero neste incidente. | a mesma série, com `resultado` em `verificacao`, `estorno_falhou` ou `sem_cobranca` |
  > | Duration, a duração | p50, p99 e a fração das chamadas acima de 1 s, por versão | `histogram_quantile` sobre `checkout_operacao_duracao_seconds_bucket` |
  >
  > A duração é um histograma: cada chamada soma 1 num balde. Os baldes deste projeto, em segundos, são 0,05, 0,1, 0,25, 0,5, 1, 2, 4, 8 e +Inf. Guardar o balde custa um contador. Guardar cada pedido custaria uma linha por request, e a 5.000 TPS isso é o problema de cardinalidade da seção de labels. O percentil exato que aparece nos testes usa as amostras só dentro do teste, com teto de 20.000, para mostrar o número verdadeiro ao lado da estimativa do balde. O painel usa o balde.
  >
  > A média é a soma das durações já terminadas dividida pela quantidade de chamadas já terminadas. Ela cabe numa conta e esconde o que o cliente sentiu. Os painéis do incidente mostraram 120 ms. Eles não mostraram quantas chamadas estavam fora dessa média. O exemplo abaixo é uma distribuição que produz exatamente essa média, para ficar visível o tamanho do buraco. É um exemplo, não a medição daquela noite: a medição não existia.
  >
  > 9.800 chamadas terminam em 0,07 s e 200 terminam em 2,57 s.
  >
  > - Soma = 9.800 × 0,07 + 200 × 2,57 = 686 + 514 = 1.200
  > - Média = 1.200 / 10.000 = 0,12 s = **120 ms**
  > - p50 = 0,07 s. Metade dos clientes levou 70 ms.
  > - p99 exato = **2,57 s**. A 9.900ª chamada, das 10.000 ordenadas, já está no grupo lento. Pelo outro jeito de ler o mesmo percentil: 98% das chamadas são menores ou iguais a 70 ms, e 99% só fecha quando o valor sobe para 2,57 s.
  > - O quantil calculado pelos baldes cai no balde que vai de 2 s a 4 s. A conta linear é 2 + (4 − 2) × (9.900 − 9.800) / 200 = **3,0 s**.
  >
  > O balde erra o valor (3,0 s no lugar de 2,57 s) porque mistura tudo que está entre 2 s e 4 s. Os dois números estão muito acima de 120 ms. Um alerta de média acima de 300 ms fica em silêncio. Um alerta de quantil 0,99 acima de 800 ms dispara. O teste `mediaDe120msEscondeP99De2570ms` trava essas contas.
  >
  > Tem um segundo jeito de a média continuar em 120 ms, e ele combina com o botão que fica carregando até o aplicativo desistir. A chamada que ainda não terminou não entra na soma. Se 4.900 chamadas/s terminam em 120 ms e 100 chamadas/s seguem abertas, a média das que terminaram é 120 ms, o HTTP 500 é zero e a CPU fica baixa, porque a thread está parada esperando o socket, não calculando.
  >
  > A lei de Little diz que a quantidade em andamento é a vazão vezes o tempo parado. A base saudável é 4.900 × 0,120 s = **588** chamadas abertas. As 100 que o aplicativo segura por 8 s (8 s é **estimativa**: o enunciado diz que o aplicativo desiste por timeout e não diz quantos segundos) somam 100 × 8 = **800**. Total = **1.388**. O dobro da base é 1.176. 1.388 passa de 1.176, então o alerta de "em andamento" dispara, e o alerta da média não. O teste `requisicoesAindaEmAndamentoNaoEntramNaMedia` usa esses inteiros. O limiar do alerta é o dobro da base medida em 10 min, não os 8 s: os 8 s só montam este exemplo. Se o timeout do aplicativo for outro, a conta do exemplo muda e o limiar não. O medidor no ar é `checkout_http_em_andamento`. O projeto também publica `checkout_pedidos_em_aberto`, que conta pedido fora de `CONCLUIDO`, `CANCELADO_SEM_COBRANCA` e `CANCELADO_COM_ESTORNO` (no laboratório, `CONCLUIDO` se chama `CONFIRMADO`). Esse segundo medidor pega o caso em que o HTTP respondeu 202 em 120 ms e a saga ficou parada: a média HTTP é honestamente 120 ms, e o pedido continua em `AGUARDANDO_PAGAMENTO` ou num estado de verificação.
  >
  > O canary pede o mesmo histograma separado por `versao`. Misturar as dez réplicas antes de tirar o percentil também esconde o problema. Exemplo com números que o teste `mediaDosP99EntreReplicasEscondeOCanary` reproduz:
  >
  > - 9 réplicas estáveis, 1.000 chamadas cada, todas a 100 ms. O p99 de cada uma é 100 ms.
  > - 1 réplica do canary, 1.000 chamadas: 800 a 100 ms e 200 a 7 s. O p99 dessa réplica é 7 s.
  > - A média desses dez p99 é (9 × 100 + 7.000) / 10 = **790 ms**. Um alerta "média dos p99 acima de 800 ms" fica em silêncio.
  > - No conjunto são 9.800 chamadas a 100 ms e 200 a 7 s. O p99 verdadeiro é 7 s. O balde de 4 s a 8 s estima **6,0 s** (4 + (8 − 4) × 100 / 200). 6 s dispara o alerta de 800 ms.
  > - A média geral é (9.800 × 0,1 + 200 × 7) / 10.000 = **238 ms**. Um alerta de média acima de 300 ms também fica em silêncio.
  >
  > A consulta que soma os baldes e só então tira o percentil:
  >
  > ```promql
  > histogram_quantile(0.99, sum by (le, versao) (rate(checkout_operacao_duracao_seconds_bucket[1m])))
  > ```
  >
  > O `sum by (le, versao)` junta as réplicas da mesma versão e mantém v1 e v2 separadas. Somar os baldes primeiro é o que faz o 6,0 s aparecer. Tirar o p99 em cada réplica e depois fazer a média é a conta que deu 790 ms.
  >
  > Além das três séries RED, o painel tem a conversão: pedidos que chegam a `CONCLUIDO` dividido pelos que entraram em `PENDENTE`, por versão (no laboratório esses dois estados se chamam `CONFIRMADO` e `RECEBIDO`). No incidente a conversão foi o número que caiu, e ela não estava no painel. **Decisão** de alerta, para a próxima abertura:
  >
  > | Alerta | Dispara | Fica em silêncio neste incidente, de propósito |
  > | :--- | :--- | :--- |
  > | p99 de `finalizar` acima de 800 ms por 2 min, com pelo menos 100 chamadas na janela | no exemplo, 3,0 s e também 6,0 s | |
  > | `checkout_http_em_andamento` acima do dobro da base de 10 min, por 1 min | 1.388 contra base 588 | |
  > | `checkout_pedidos_em_aberto` ou estado de verificação com mais de 5 min | a saga parada depois do HTTP 202 | 5 min é **decisão**. Em produção um scheduler chama o job a cada 1 min, então 5 min já são várias tentativas. Neste projeto o teste chama `reconciliar` direto |
  > | conversão abaixo de 0,97 por 3 min | a queda que o suporte viu | 0,97 é **decisão**; a aula não publica a conversão normal |
  > | média acima de 300 ms | | 120 ms e 238 ms, os dois exemplos |
  > | CPU acima de 80% como único sinal | | a CPU do incidente estava abaixo de 40% |
- **Visão dos Recursos (USE):** Que recursos você monitora? Que sinal acusaria saturação antes do primeiro HTTP 500?
  > USE, do método do Brendan Gregg, é a visão de cada recurso por dentro. Para cada recurso: utilização (fração do tempo ou da capacidade ocupada), saturação (fila de quem espera) e erros (falhas daquele recurso). O RED diz se o cliente sofreu. O USE diz qual peça encheu. Os dois ficam no mesmo painel porque o incidente teve os dois sintomas ao mesmo tempo: cliente esperando, e CPU abaixo de 40% com zero HTTP 500.
  >
  > | Recurso | Utilização | Saturação, o sinal que vem antes do 500 | Erros |
  > | :--- | :--- | :--- | :--- |
  > | Pool de conexões do PostgreSQL (HikariCP) | conexões em uso / tamanho do pool | threads esperando conexão (`fila > 0`) | timeout ao pegar conexão |
  > | Pool do cliente HTTP que chama o PSP e o Estoque | chamadas em uso / limite | chamadas esperando uma vaga | timeout de conexão |
  > | Consumidor da saga no Kafka | registros em processamento / paralelismo | lag do tópico, em mensagens e em segundos | falha ao processar, indo para retry |
  > | Dinheiro em Pix ainda não confirmado | soma em `AGUARDANDO_PAGAMENTO` / teto medido | pedido novo recusado pelo teto, ainda com HTTP 200 e estado `CANCELADO_SEM_COBRANCA` | pedido em `ESTORNO_FALHOU` (24 h depois das cinco tentativas, com consulta diária) |
  >
  > O exemplo que o teste `poolSaturadoDisparaComCpuBaixaEZeroHttp500` trava: pool de 10 conexões, as 10 ocupadas, fila de 200, CPU em 15%, HTTP 500 em zero. A utilização é 10/10 = 1. O alerta olha a fila, a utilização em 100% e a CPU abaixo de 40%. Com a fila em zero o mesmo alerta fica em silêncio. A CPU baixa é compatível com a fila cheia: a thread parada no `socketRead` não gasta CPU. Por isso um alerta só de CPU não vê este recurso.
  >
  > Com threads virtuais, a contagem de threads também engana. Elas aguentam a espera. O limite volta a ser o pool. A regra que protege o pool é a da Aula 2, aplicada aqui: a transação do banco abre, grava o estado, fecha, e só então a chamada ao PSP acontece. Nenhuma conexão fica presa durante os segundos do parceiro.
  >
  > O lag do Kafka é o sinal do caso "HTTP respondeu em 120 ms". O `finalizar` publicou o evento e devolveu 202. Se o consumidor parar, a CPU do Checkout continua baixa, o HTTP 500 continua zero, e o lag sobe junto com `checkout_pedidos_em_aberto`.
  >
  > O teto de pendente existe para um defeito que nunca confirma o Pix não criar exposição sem fim, também com zero HTTP 500. A aula não diz a fração de Pix nem o ticket, então um valor em reais chutado não vira limiar. A conta 20% × 5.000 TPS × 30 s × R$ 180 = R$ 5.400.000 só mostra a forma (vazão × fração × tempo aberto × ticket). Os insumos não estão no enunciado e não entram no alerta. **Decisão:** o teto global é 1,5 vez a soma medida de Pix em `AGUARDANDO_PAGAMENTO` na hora anterior à abertura. O teto por cliente é o maior valor em aberto que o negócio já aceitou, também medido. Enquanto essa base não existe, o sinal que vale é a contagem de pedidos em `AGUARDANDO_PAGAMENTO` e em estado de verificação, não um número de reais. Passou do teto medido, a chamada ao PSP não acontece, o estoque reservado é liberado e o estado é `CANCELADO_SEM_COBRANCA`. O teste usa um teto pequeno de propósito (R$ 100 por cliente e dois pedidos de R$ 80). Os números do `application.yml` (R$ 2.000 e R$ 8.000.000) são fixture para o laboratório subir. Não são o alerta de produção.
- **Labels:** Quais labels você permite nessas métricas e quais proíbe? Diga o efeito de cada escolha no servidor de métricas.
  > No Prometheus, cada combinação diferente de labels é uma série temporal nova. O servidor aguenta mal quando o conjunto de valores cresce sem teto. A documentação de nomes e labels diz isso com as palavras user id e e-mail: não são label.
  >
  > Labels permitidos, e o teto de cada um:
  >
  > | Label | Valores | Quantos |
  > | :--- | :--- | :--- |
  > | `servico` | `checkout`, `pagamento`, `estoque` | 3 |
  > | `operacao` | `finalizar`, `reservar`, `cobrar`, `estornar`, `confirmar` | 5 |
  > | `resultado` | `ok`, `sem_cobranca`, `estornado`, `estorno_falhou`, `pix_pendente`, `verificacao` | 6 |
  > | `versao` | `v1` e `v2` enquanto o canary existir | 2 |
  >
  > O teto é 3 × 5 × 6 × 2 = **180** combinações. Um histograma clássico com estes baldes vira 9 séries de balde mais `_sum` e `_count`, 11 séries por combinação. 180 × 11 = **1.980** séries para esta métrica. É um número fixo, independente de ter 5.000 pedidos por segundo. O teste manda 1.000 observações variando só `v1` e `v2` e confere que nascem 2 séries, não 1.000.
  >
  > Labels proibidos:
  >
  > | Label | O que acontece no servidor de métricas |
  > | :--- | :--- |
  > | `order_id` | 5.000 pedidos/s × 60 s = 300.000 ids num minuto. Vezes 11 séries do histograma = **3,3 milhões** de séries por minuto, numa métrica só. Se a cabeça do Prometheus segura 15 min (exemplo comum de retenção curta, não é um número da aula), são 4,5 milhões de pedidos × 11 = cerca de 50 milhões de séries. O teste recusa `order_id` com exceção, e o texto publicado em `/checkout/metricas` não contém essa palavra. O `order_id` continua no log, onde ele é um campo de uma linha, não uma série nova a cada pedido. |
  > | `trace_id` | Um valor por requisição. A mesma explosão do `order_id`. O trace já guarda esse id. |
  > | `user_id` | Uma série por cliente. No pico da Black Friday o conjunto cresce o tempo todo, e o id da pessoa iria parar no banco de métricas. |
  > | `cpf` e `email` | O mesmo crescimento, e ainda é dado pessoal dentro do TSDB. O teste recusa os dois, inclusive quando o CPF vem disfarçado no valor de um label permitido. |
  > | `sku_id` | O catálogo tem milhares de produtos. Um produto quente não torna o label seguro: os outros produtos também criam série. O `skuId` também seria uma péssima chave de partição, como a seção 1 mostrou com o `FONE-BF`. |
  >
  > `resultado` usa o nome do grupo (`verificacao`), não o texto livre da mensagem. Se a mensagem entrasse como label, cada frase nova seria uma série.

---

## 3. Traces e Amostragem

- **A Escolha:** ( ) Head Sampling  (X) Tail Sampling  ( ) Combinação das duas
- **Justificativa:** Guardar 100% dos traces a 5.000 TPS custa caro. Qual é a sua regra de retenção, e como ela evita que justamente as requisições que travaram sejam descartadas?
  > A regra é tail sampling no OpenTelemetry Collector, com a decisão depois que os spans da requisição chegaram. O SDK em cada serviço usa o amostrador que registra tudo (`AlwaysOn`) e envia os spans para o collector no mesmo cluster. O collector aplica, nesta ordem:
  >
  > 1. Guarda 100% dos traces com algum span em erro.
  > 2. Guarda 100% dos traces com duração acima de **800 ms**. 800 ms é **decisão**: fica perto de sete vezes a média de 120 ms que o painel mostrou, e é o mesmo limiar do alerta de p99.
  > 3. Guarda 100% dos traces cujo pedido terminou num estado de verificação ou em `ESTORNO_FALHOU`.
  > 4. Dos traces que sobraram, guarda **1%**, escolhidos pelo trace-id. A amostra do caminho saudável continua representativa. A documentação do OpenTelemetry lembra que, em vazão alta, 1% ou menos já representa o resto.
  >
  > O 1% do item 4 também é decidido no collector, olhando o trace que já terminou. Ele é uma das políticas do processador de tail sampling (a política probabilística), ao lado da política de latência e da de status. Head sampling seria decidir na entrada do SDK, antes de existir duração. Por isso a marcação acima é tail sampling, e não a combinação das duas.
  >
  > Por que a decisão precisa ser no fim. O trace-id `...0064` (o inteiro 100 formatado em 32 hex) cai fora do corte de 1%: o resto da divisão por 10.000 é 100, e o corte do 1% é "menor que 100". Se a decisão fosse na entrada, um pedido com esse trace-id e duração de 2,57 s seria descartado, junto com cerca de 99 de cada 100 pedidos lentos. A gente ficaria com um exemplo por segundo e perderia o pedido 48213 no meio do chamado. Com a decisão no fim, 2,57 s é maior que 0,8 s e o trace é guardado. O mesmo trace-id com 0,07 s e sem erro é descartado. Com erro, é guardado mesmo durando 0,07 s. O teste `headDescartaOTraceLentoETailGuarda` faz essas três conferências. O trace-id do inteiro 50 cai dentro do 1% e seria guardado mesmo sendo rápido: é a amostra representativa.
  >
  > A combinação "head primeiro, tail depois" é o desenho que a documentação descreve para proteger o cano quando o collector não aguenta receber tudo. Ela não serve aqui. O que o head joga fora na entrada nunca chega ao tail, então a regra dos 800 ms não tem como resgatar a chamada que travou. A conta de memória abaixo diz que o collector aguenta receber tudo por uma janela curta.
  >
  > Custo, com cada premissa marcada:
  >
  > - **Estimativa:** 6 spans por pedido (entrada, checkout, reservar, cobrar, confirmar, evento) e cerca de 1 KB por span.
  > - Guardar 100% até o backend: 5.000 × 6 KB = 30 MB/s, cerca de **2,6 TB/dia** antes de réplica de disco.
  > - No exemplo em que 2% travam (100 pedidos/s) e guardamos 1% do restante (49/s), seguem para o backend cerca de 150 traces/s, perto de 3% do volume, cerca de **80 GB/dia**.
  > - A janela de decisão é **15 s** (**decisão**). Ela é maior que os 8 s estimados do timeout do aplicativo, para o span lento já estar dentro do trace na hora de decidir. Traces em voo = 5.000 × 15 = 75.000. Memória ≈ 75.000 × 6 KB = **450 MB** (**estimativa**). O pedido de memória do collector é **2 GB** (**decisão**), folga sobre essa conta, e o próprio collector tem alerta de fila. Se a fila encher, a documentação avisa que o tail passa a degradar; o alerta existe para vermos isso antes de começar a perder justamente o trace lento.
  >
  > O histograma da seção 2 não entra nessa amostragem. Toda chamada soma no balde. O histograma é o censo: ele vê os 2% mesmo quando não abrimos os 5.000 traces. O trace guardado pelo tail é o exemplo que a gente abre depois que o alerta disparou, já com o `trace_id` que estava na linha de log.
  >
  > O prazo em que o trace fica guardado depois de aceito é o da LGPD na seção 1: 7 dias (**decisão**).

---

## 4. Estratégia de Testes

- **Qual a nossa estratégia para testar isso e garantir que funciona?** Antes da próxima Black Friday, como você prova que os painéis e os alertas mostrariam este incidente?
  > O incidente foi visto pelo cliente e não pelo painel. O painel tinha média, CPU e HTTP 500, e os três ficaram verdes: 120 ms, CPU abaixo de 40%, zero pico de 500. O sintoma foi outro: o botão "Finalizar Pedido" carregando até o aplicativo desistir, e a conversão caindo. O postmortem, no formato blameless do SRE book, separa as duas coisas. O sintoma é o que o cliente viu. Os fatores que deixaram o sintoma invisível são do sistema, não de quem leu o painel. O teste abaixo falha se um desses buracos voltar.
  >
  > **O que falta em cada serviço para achar o travamento.** Hoje os três escrevem texto livre, cada um do seu jeito, sem um id compartilhado.
  >
  > | Serviço | O que falta hoje | O que passa a expor |
  > | :--- | :--- | :--- |
  > | Checkout | média sem histograma, canary sem série por versão, nenhum estado do pedido no log | `trace_id`, `order_id`, `estado`, histograma por `versao`, chamadas em andamento, conversão |
  > | Pagamento | a chamada ao PSP não aparece no mesmo trace; timeout vira silêncio | o mesmo `traceparent`, span da chamada ao PSP, `estado` `PAGAMENTO_EM_VERIFICACAO` ou `ESTORNO_EM_REVISAO`, duração de `cobrar` e de `estornar` |
  > | Estoque | a reserva não tem span nem estado quando a resposta não volta | o mesmo `traceparent`, span de reservar e de confirmar, `estado` `ESTOQUE_EM_VERIFICACAO` ou `CONFIRMACAO_EM_VERIFICACAO` |
  >
  > **Mitigação enquanto a causa é desconhecida.** A tabela de hipóteses abaixo ainda não fechou. Duas ações não dependem de saber qual linha está certa, e as duas são reversíveis.
  >
  > 1. **Canary.** Abortar o rollout e devolver 100% do tráfego à frota estável (o abort da Aula 5, pré-aprovado). Se a `v2` for a causa, o cliente novo deixa de cair nela. Se a `v1` estiver igual, o abort não cura, mas tira uma variável. Não se desfaz migração e não se reinicia a frota inteira no escuro.
  > 2. **Cliente.** Enquanto `checkout_http_em_andamento` ou a contagem de estados de verificação estiver acima do limiar, o Checkout responde `503` com `Retry-After` nos `finalizar` novos, em vez de segurar a tela até o aplicativo desistir. O app mostra "tente de novo" e repete com a mesma chave de idempotência. Pedido que já está em verificação não é cancelado e não é estornado: o job só muda o estado depois de consultar o participante. Não se mostra compra concluída.
  >
  > **Hipóteses.** Cada linha se confirma ou se descarta com um sinal que o desenho acima passa a ter. A investigação fecha a tabela; não escolhe a causa pelo sintoma.
  >
  > | Hipótese | Sinal que confirma | Sinal que descarta |
  > | :--- | :--- | :--- |
  > | PSP lento ou mudo | p99 ou em andamento de `cobrar` alto; o trace do pedido parado no span do PSP; sobe `PAGAMENTO_EM_VERIFICACAO`; CPU baixa | span do PSP curto; o estado parado é estoque ou confirmação; o lag do Kafka sobe com o PSP saudável |
  > | Pool de conexões do banco cheio | fila do pool > 0, utilização 100%, CPU baixa, HTTP 500 ainda zero | fila do pool em zero enquanto as chamadas em andamento sobem |
  > | Consumidor da saga parado (lag) | HTTP 202 perto de 120 ms, `checkout_pedidos_em_aberto` sobe, lag do tópico sobe em mensagens e em segundos | lag estável e o atraso está no span HTTP, antes do 202 |
  > | Réplica do canary (`v2`) | p99 da `versao=v2` alto e p99 da `v1` estável. A média dos p99 por réplica pode ficar em silêncio (os 790 ms da seção 2) | `v1` e `v2` com o mesmo p99 |
  > | Estoque mudo | sobe `ESTOQUE_EM_VERIFICACAO`; span do estoque longo; esses traces têm zero cobranças | o estado parado é pagamento ou confirmação; o estoque responde rápido no trace |
  >
  > **Postmortem.** Sintoma: tela carregando e conversão caindo. Fatores contribuintes: log sem id compartilhado nos três serviços, duração publicada como média, saturação de pool e lag fora do painel, conversão fora do canary, e um estado que não dizia se o dinheiro tinha se movido. Quem olhou o painel viu verde porque o painel só tinha média, CPU e HTTP 500. Isso não é a causa do travamento; é o que impediu de vê-lo. A causa fica na linha da tabela que os sinais fecharem. Até lá, o postmortem não nomeia um culpado nem uma hipótese como fato.
  >
  > A prova roda em dois níveis. Os dois usam a mesma régua: com o cenário do incidente injetado, o alerta de quantil ou o de trabalho em andamento dispara, e o alerta de média acima de 300 ms fica em silêncio. Essa segunda conferência é a verificação negativa. Passar no caminho feliz não basta. O teste também falha se aparecer o estado proibido.
  >
  > **1. Os números do painel, sem subir a Black Friday.** `TelemetriaTest` alimenta o histograma com as distribuições da seção 2 e chama a mesma função de alerta que o painel usaria.
  >
  > | Teste | O que tem de ser verdade | O que faz o teste falhar |
  > | :--- | :--- | :--- |
  > | `mediaDe120msEscondeP99De2570ms` | média 120 ms, p99 exato 2,57 s, quantil do balde 3,0 s, alerta de 800 ms ligado | alerta de média acima de 300 ms ligado |
  > | `mediaDosP99EntreReplicasEscondeOCanary` | média dos p99 = 790 ms e em silêncio; quantil somado = 6,0 s e disparado; média geral 238 ms | misturar as versões e tratar 790 ms como o p99 do conjunto |
  > | `requisicoesAindaEmAndamentoNaoEntramNaMedia` | 1.388 em andamento dispara; a base 588 não dispara; a média das concluídas continua 120 ms | |
  > | `poolSaturadoDisparaComCpuBaixaEZeroHttp500` | fila 200, pool 10/10, CPU 15%, HTTP 500 = 0, alerta ligado | o mesmo alerta com fila zero |
  > | `labelOrderIdOuCpfEProibidoENaoMultiplicaSerie` | 1.000 pedidos geram 2 séries | `order_id`, `cpf` ou um CPF dentro de um label permitido |
  > | `headDescartaOTraceLentoETailGuarda` | duração 2,57 s é guardada no tail; 0,07 s sem erro é descartada; erro é guardado | |
  > | `mdcNaoAtravessaThreadETraceparentAtravessa` | a thread nova vê o mapa vazio; o evento com `traceparent` devolve o mesmo trace-id | |
  > | `traceparentRejeitaIdSoDeZerosELogNaoLevaCpf` | o cabeçalho só de zeros é rejeitado; a linha tem `trace_id` e `order_id` e não tem o CPF | |
  >
  > **2. O pedido, com participante mudo.** `SagaDiagnosticoTest` usa Estoque e PSP scriptados. A regra, em toda etapa, é a mesma: se a resposta não chega, consulta o status antes de decidir. Enquanto o status for desconhecido, o pedido fica no estado de verificação. Não confirma, não cancela e não estorna. Onde a tabela diz `CONFIRMADO`, o laboratório afirma esse sinônimo; no apêndice o estado é `CONCLUIDO`.
  >
  > | Situação | Estado esperado | Verificação negativa |
  > | :--- | :--- | :--- |
  > | Estoque responde sem saldo | `CANCELADO_SEM_COBRANCA` | zero cobranças, zero estornos, e um `PagamentoConfirmado` atrasado não muda o estado |
  > | Estoque não responde e a consulta é desconhecida | `ESTOQUE_EM_VERIFICACAO`, também depois do job | zero cobranças e zero estornos |
  > | Estoque não responde e a consulta diz que não reservou | `CANCELADO_SEM_COBRANCA` | zero cobranças; não houve reserva, então não há liberação |
  > | PSP não responde e a consulta é desconhecida | `PAGAMENTO_EM_VERIFICACAO` | zero estornos e zero liberações de estoque; a resposta não traz a soma pendente nem CPF |
  > | PSP não responde e a consulta diz recusado | `CANCELADO_SEM_COBRANCA` | exatamente uma liberação de estoque e zero estornos |
  > | PSP não responde e a consulta diz pago | `CONFIRMADO` | zero estornos, e a segunda chamada do mesmo `orderId` não cobra de novo |
  > | Reserva expira depois do pagamento | `CANCELADO_COM_ESTORNO` | exatamente uma chamada de estorno, mesmo com o webhook `ESTORNADO` duas vezes e o evento `ReservaExpirou` duas vezes |
  > | Confirmação do estoque não responde e a consulta diz que a reserva expirou | `CANCELADO_COM_ESTORNO` | a consulta aconteceu antes do estorno |
  > | Confirmação não responde e a consulta é desconhecida | `CONFIRMACAO_EM_VERIFICACAO` | zero estornos e o estado não é `CONFIRMADO`, embora o dinheiro já tenha sido capturado |
  > | Estorno não responde; o job depois vê "estornado" | `CANCELADO_COM_ESTORNO` | nenhuma chamada nova de estorno |
  > | Job consulta o estorno e ele continua desconhecido | continua em `ESTORNO_EM_REVISAO` | nenhuma chamada nova de estorno |
  > | Dentro de 24 h o PSP diz que ainda não estornou | uma retentativa, e `CANCELADO_COM_ESTORNO` se ela voltar "estornado" | a retentativa é uma chamada, não duas, naquela execução do job |
  > | 24 h sem confirmação de estorno | `ESTORNO_FALHOU` e um alerta financeiro; no dia seguinte a consulta ainda pode fechar em `CANCELADO_COM_ESTORNO` | nenhuma chamada nova de estorno nesse fechamento |
  > | Segundo pedido estoura o teto de pendente | `CANCELADO_SEM_COBRANCA` | zero chamadas de cobrança para esse segundo pedido, estoque liberado uma vez |
  > | Webhook com assinatura errada | o estado não muda | nenhuma confirmação de estoque |
  > | Webhook `PAGO` com HMAC certo, e o mesmo de novo | `CONFIRMADO` na primeira | a segunda não confirma o estoque outra vez |
  >
  > O evento publicado nesses testes carrega o `traceparent` da entrada e usa o `orderId` como chave de partição. Para o par 48213 e `FONE-BF`, as duas chaves caem em partições diferentes, e o teste confere que a chave usada foi a do pedido.
  >
  > **3. A aplicação de verdade sobe.** `SmokeApplicationTest` sobe o Spring Boot, vê `/actuator/health/readiness` com 200, finaliza o pedido 48213 até `CONFIRMADO`, recusa um corpo com CPF, lê `/checkout/metricas` e falha se o texto tiver `order_id` ou `cpf`, e manda um webhook sem assinatura válida, que volta 401 sem tirar o pedido de `CONFIRMADO`.
  >
  > **4. O spike, antes da Black Friday, no ambiente de staging.** O roteiro está em `desafio/k6/spike-telemetria.js`, no formato de spike do Grafana k6: a carga sobe reto e desce reto, porque o pico real não tem rampa longa. A forma:
  >
  > | Fase | Duração | Chegadas |
  > | :--- | :--- | :--- |
  > | Base | 1 min | 500/s |
  > | Subida | 10 s | até 5.000/s |
  > | Pico | 1 min | 5.000/s |
  > | Descida | 10 s | de volta a 500/s |
  > | Recuperação | 1 min | 500/s |
  >
  > Durante o pico, 2% das chamadas de estoque dormem 8 s. É a mesma fração e a mesma espera usadas na conta das chamadas em andamento. O spike passa só se, em até 30 s, o alerta de quantil ou o de em andamento estiver disparado, o alerta de média acima de 300 ms estiver em silêncio, um trace lento tiver o mesmo `trace_id` no Checkout e no Estoque, a saída de métricas não tiver `order_id`, `user_id` nem `cpf`, e, no minuto de recuperação, `checkout_http_em_andamento` voltar para perto da base. Pedido que ficou no meio está num estado de verificação, nunca em `CONCLUIDO` (o laboratório afirma o sinônimo `CONFIRMADO`), e o job só o move depois de consultar o status.
  >
  > O `mvn verify` roda os níveis 1 a 3: 26 testes. O k6 é o nível 4, executado em staging quando a ferramenta e o collector estão de pé. Os dois olham a mesma régua. Se o alerta de média for o único alerta ligado no pipeline, o nível 1 já quebra, antes de chegar na Black Friday.

## Referências usadas

OpenTelemetry, "What is OpenTelemetry?" e "Sampling" (head sampling decide no início, pelo trace-id, e não consegue guardar todo trace com erro; tail sampling decide com os spans em mãos e por isso guarda erro e latência; a combinação que amostra na entrada existe para proteger o cano e perde o que foi descartado); W3C Trace Context, formato do `traceparent` (`00-trace-id-span-id-flags`, ids só de zeros inválidos); Lei 13.709/2018, art. 5º, XI, art. 6º, III, art. 12 e art. 13, §4º; Grafana Labs, "The RED Method" (Tom Wilkie: rate, errors, duration, por serviço); Brendan Gregg, "The USE Method" (utilization, saturation, errors, por recurso); Prometheus, "Metric and label naming" (label de alta cardinalidade, com o exemplo de user id) e "Histograms and summaries" (`histogram_quantile` depois do `sum by (le)`; a média de quantis pré-calculados não é o quantil do conjunto); Google SRE Book, "Postmortem Culture" (sintoma e fatores do sistema, sem culpa de quem leu o painel que existia) e "Effective Troubleshooting"; Grafana k6, "Spike testing" (subida e descida curtas, monitoramento do backend como parte do teste).
