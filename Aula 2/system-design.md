# Template de System Design: Aula 02 - APIs Resilientes

> **Instruções:** Faça uma cópia deste arquivo e preencha as seções abaixo com as conclusões da sua pesquisa e os debates. O foco é a integração da API de Checkout com o Serviço de Pagamento Externo para suportar 10.000 usuários simultâneos.

YAML, tabelas e o diagrama estão no [anexo](anexo-detalhes.md). A linha de pagamento segue a [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento).

## 1. Escolha de Paradigma da API

- **A Escolha:** (x) Spring MVC com Virtual Threads ou ( ) Spring WebFlux (Reativo)?
- **Justificativa:** Considerando a capacidade de suportar 10k acessos concorrentes, por que você fez essa escolha técnica?
  > A escolha é Spring MVC com threads virtuais.
  > Thread virtual é uma espera barata. Ela não ocupa uma thread do sistema operacional.
  > A carga desta API é espera de rede. O código segue a ordem normal, com JDBC e Resilience4j (a biblioteca do timeout e do disjuntor).
  > WebFlux fica de fora. Não há fluxo contínuo de dados nem banco reativo.
  > Dez mil esperas gastam memória. O limite real é o pool do banco. O HikariCP (esse pool) começa com 10 conexões. Nenhuma fica aberta durante a chamada ao parceiro.
  > No Java 21, `synchronized` em volta de uma espera prende a thread virtual. Use `ReentrantLock`. Detalhe: [anexo, paradigma](anexo-detalhes.md#paradigma).

## 2. Desenho de Resiliência

Dependemos de uma API terceira instável. Quais recursos do padrão de resiliência você vai utilizar?

- **Proteção contra Falhas e Lentidão:**
  Quais padrões (Circuit Breaker, TimeLimiter, RateLimiter, Bulkhead, Retry) você implementaria na chamada para o Parceiro de Pagamentos? Por que?
  > A chamada usa timeout, disjuntor, retentativa curta, bulkhead e limite de taxa.
  > Disjuntor: depois de muitas falhas, paramos de chamar o parceiro por um tempo. Bulkhead: um teto de chamadas ao mesmo tempo. Limite de taxa: um teto de chamadas por segundo.
  > O TimeLimiter não entra. A anotação dele não corta uma chamada bloqueada. Quem corta é o timeout do cliente HTTP.
  > Timeout de leitura e HTTP 500 são ambíguos. Não repetimos esses casos. Recusa 402, fila cheia e cota estourada não abrem o disjuntor. O parceiro não foi chamado.
  > Conexão em 1 s e resposta em 2 s. Bulkhead de 200, com 50 ms para conseguir uma vaga. Retentativa no máximo 3, só falha de conexão e HTTP 503 ou 429, sempre a mesma chave.
  > Disjuntor aberto, bulkhead cheio ou cota estourada: respondemos 503 com `Retry-After`, sem linha de pagamento.
  > Timeout ou 500 depois de enviar: 202 com `PENDENTE`. A reconciliação só fecha essa linha com `WHERE status = 'PENDENTE'`.
  > A única saída de um estado final: um webhook tardio muda `EXPIRADO` para `APROVADO`. O pedido em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO`. Ordem e YAML: [anexo, padrões](anexo-detalhes.md#padroes).

- **Proteção contra Duplicidade:**
  Como a `Idempotency-Key` será utilizada na requisição do Checkout para evitar cobranças duplas? Qual o ciclo de vida dessa chave?
  > A chave é da intenção de compra. Idempotência: repetir o pedido não cobra duas vezes.
  > O cliente gera um UUID e reenvia o mesmo. Sem o cabeçalho, a resposta é 400.
  > O `INSERT` com `UNIQUE (chave)` decide quem chegou primeiro. A mesma chave segue para o parceiro. `UNIQUE (order_id)` é a segunda trava.
  > Mesma chave e mesmo corpo já concluído devolvem a resposta guardada: 201, 402 ou 202. Corpo diferente: 422. Ainda em processamento: 409.
  > Um 503 sem chamada libera a chave e não cria pagamento. Timeout ou 500 grava `PENDENTE`. A chave vale 24 h.
  > Se a réplica cair, o prazo `travado_ate` vence. Com cobrança no parceiro, a reconciliação insere a linha. Sem cobrança, libera a chave.
  > Cartão recusado encerra o pedido. Outro cartão é um checkout novo.
  > O `CHECK` de `pagamento.status` é `PENDENTE`, `AGUARDANDO_CAPTURA`, `APROVADO`, `RECUSADO` e `EXPIRADO`. Ciclo e diagrama: [anexo, idempotência](anexo-detalhes.md#idempotencia).

## 3. Os Edge Cases (Engenharia do Caos)

Cole aqui as 3 perguntas de risco que você formulou e defina como a arquitetura responde a cada uma delas.

1. **E se...** o parceiro ficar lento sem chegar a dar timeout (1,8 s por resposta) e milhares de compras ficarem esperando ao mesmo tempo, travando o checkout inteiro?
   - **Resposta do Sistema:** Threads virtuais aguentam a espera. O limite é o pool do banco. Nenhuma conexão fica aberta durante a chamada. O bulkhead deixa no máximo 200 chamadas ao mesmo tempo. Quem não pega vaga em 50 ms recebe 503, sem cobrança. Essa rejeição não conta como falha do disjuntor. O disjuntor também olha lentidão: 60% das chamadas acima de 1,5 s o abrem, mesmo sem erro. Ele fica aberto 30 s e testa a volta com 5 chamadas.
2. **E se...** o disjuntor ficar aberto e, quando o parceiro volta, todos os clientes reenviarem ao mesmo tempo, derrubando o parceiro de novo?
   - **Resposta do Sistema:** A cota é uma só para a frota. Cada réplica recebe a sua parte. O excesso toma 503 na nossa porta, sem chegar ao parceiro. As retentativas usam espera crescente com sorteio, para não caírem no mesmo instante. O reenvio usa a mesma chave. Não há segunda cobrança. Lacuna: o `Retry-After` que devolvemos é fixo. Sortear de 20 a 40 s espalharia a volta dos aplicativos.
3. **E se...** o cliente reenviar o mesmo checkout (duplo clique ou retry do app) e as duas requisições, com a mesma `Idempotency-Key`, chegarem ao mesmo tempo em réplicas diferentes?
   - **Resposta do Sistema:** As duas tentam gravar a chave. O `UNIQUE` do banco deixa uma vencer. A vencedora chama o parceiro. A perdedora, se a chave ainda está em processamento, recebe 409. Se a primeira já concluiu, a segunda recebe a resposta guardada. Um lock na memória não serve. As réplicas não dividem memória.

## 4. Testando a Resiliência

- **Estratégia:** Como você vai garantir que o Circuit Breaker abre na hora certa e o Timeout não deixa a Thread presa?
  > O teste sobe o Spring de verdade, com WireMock no lugar do parceiro. WireMock é um servidor falso.
  > Cada teste nasce vermelho. Falha de rede usa Testcontainers e Toxiproxy.
  > Uma resposta de 10 s volta em cerca de 2 s, com 202 e `PENDENTE`, nunca 201. As vagas do bulkhead voltam.
  > Depois do mínimo de chamadas, o disjuntor abre. A próxima responde 503 e o WireMock não recebe nada. Timeout gera exatamente 1 chamada.
  > Duas requisições com a mesma chave: uma 201 e uma 409. Recusa 402 deixa o disjuntor fechado. Outro cartão na mesma chave: 422.
  > No sandbox do parceiro, a mesma chave cobra uma vez. Tabela: [anexo, testes](anexo-detalhes.md#testes).
