# Desafio Aula 2 (PE-JV-003): pagamento resiliente com Spring Boot + Resilience4j

Enunciado oficial: [`PE-JV-003-Aula-02-Desafio-Codigo.md`](../Originais/PE-JV-003-Aula-02-Desafio-Codigo.md). Desenho da aula: [`../system-design.md`](../system-design.md) (checkout, MVC + threads virtuais, `Idempotency-Key`, `PENDENTE` + reconciliação).

## O que o desafio pede (em uma frase cada)

1. **Cliente HTTP** para o parceiro de pagamentos (escolha livre).
2. **Idempotência**: um `record` com `idempotencyKey` que **sempre** vai no cabeçalho da chamada.
3. **Resilience4j**: circuito que abre rápido + uma estratégia justificada contra chamadas lentas.
4. **Fallback** quando o parceiro cai, documentado com **Swagger/OpenAPI**.
5. **`@RestControllerAdvice`** devolvendo erros no formato **Problem Details (RFC 7807)**, sem stack trace.
6. **Modelo mínimo**: entra `id`, `valor`, `idempotencyKey`; sai `id`, `status`.
7. **Testes de integração com WireMock** (lentidão, circuito abrindo, circuito aberto) e os "corner cases" (connect vs read timeout, 5xx/429, OPEN→HALF_OPEN→CLOSED, reenvio após timeout, fallback que falha).

## Glossário rápido (para quem vem do Python)

| Termo | O que é | No Python seria... |
|---|---|---|
| Spring Boot | framework web "com pilhas incluídas" | FastAPI/Django |
| `record` | classe imutável só de dados | `@dataclass(frozen=True)` |
| `@Annotation` | etiqueta que o Spring lê e usa para "embrulhar" o método | decorator (`@retry`) |
| `RestClient` | cliente HTTP | `requests.Session` |
| Circuit Breaker | "disjuntor": depois de muitas falhas, para de chamar o parceiro por um tempo e responde na hora | lib `pybreaker` |
| Bulkhead | limita quantas chamadas simultâneas vão ao parceiro | `asyncio.Semaphore` |
| Fallback | plano B quando a chamada falha | o `except` que devolve algo útil |
| WireMock | servidor falso que finge ser o parceiro (lento, com erro...) | `responses`/`pytest-httpserver` |
| MockMvc | chama a nossa API dentro do teste, sem abrir porta | `TestClient` do FastAPI |
| Maven (`pom.xml`) | baixa dependências e roda build/testes | `pip` + `requirements.txt` + `pytest` |

## Como a solução funciona

`POST /pagamentos` com cabeçalho `Idempotency-Key` e corpo `{"id":"pg-1","valor":100.00}`.

```
Controller -> PagamentoService -> [CircuitBreaker( Bulkhead( ParceiroClient ) )] -> parceiro POST /cobrancas
                   |                                                        (Idempotency-Key no cabeçalho)
                   +-- IdempotenciaStore (a chave já foi vista?)
                   +-- ContingenciaStore (fallback: guarda PENDENTE)
TratadorGlobalDeErros (@RestControllerAdvice) -> Problem Details
```

Regra de ouro (do system design): **falha nunca vira "aprovado"**, e a resposta muda conforme **sabemos ou não** se o parceiro cobrou:

| Situação | O parceiro pode ter cobrado? | Nossa resposta |
|---|---|---|
| Parceiro respondeu 200 | sim, e sabemos o status | `201 {id, status}` |
| Parceiro lento (read timeout de 1 s) ou 500 | **talvez** (ambíguo) | **fallback**: `202 {id, "PENDENTE"}`; uma reconciliação (fora do escopo) fecha depois |
| Conexão recusada / connect timeout | não (nada chegou lá) | `503` + `Retry-After`, Problem Details |
| Parceiro devolveu 429 ou 503 | não ("não processei") | `503` + `Retry-After` (não repassamos o 429 cru) |
| Circuito OPEN (`CallNotPermittedException`) ou bulkhead cheio | não (nem chamamos) | `503` + `Retry-After` **na hora** |
| Fallback também falhou (não conseguiu gravar PENDENTE) | talvez | `500` Problem Details, sem stack trace |
| Mesma chave, já concluída | — | a **mesma resposta guardada** (ex.: `202 PENDENTE` de novo), sem chamar o parceiro |
| Mesma chave, ainda processando | — | `409` |
| Mesma chave, corpo diferente | — | `422` |
| Sem `Idempotency-Key` | — | `400` |

Quando sabemos que nada foi cobrado (503), a chave é **liberada**: o cliente pode tentar de novo com a mesma chave.

**Decisões e porquês:**
- **Cliente: `RestClient`** com `SimpleClientHttpRequestFactory`: moderno, síncrono (combina com MVC + threads virtuais) e com timeouts nativos simples.
- **Contra lentidão: `connectTimeout` (500 ms) e `readTimeout` (1 s) do próprio cliente**, não `TimeLimiter`. Motivo: o `TimeLimiter` só funciona com `CompletableFuture` e, ao "desistir", **não interrompe** a chamada bloqueante; a thread continuaria presa. O timeout do socket corta de verdade. Descartado também o `Retry` aqui: timeout e 500 são ambíguos e repetir poderia cobrar duas vezes (o design só permite retry restrito; para manter enxuto, o "retry" fica com o cliente, usando a mesma chave).
- **Circuit Breaker**: janela de 4 chamadas, abre com 50% de falha, fica 2 s aberto, 2 chamadas de teste em HALF_OPEN. Valores pequenos **para o teste ser rápido**; em produção calibrar com métrica real (o design sugere 50/20/30 s).
- **Bulkhead (semáforo, 200)**: com threads virtuais, threads são "de graça"; o limite de verdade é quanto o parceiro aguenta.
- **Threads virtuais ligadas** em `application.yml` (`spring.threads.virtual.enabled: true`) = Variação A do enunciado.
- **Idempotência em memória** (`ConcurrentHashMap.putIfAbsent`, atômico, faz o papel do `INSERT` com `UNIQUE` do design). Simplificação do desafio: com várias réplicas teria de ser a tabela com `UNIQUE` no banco.

**Achado real ao rodar:** o primeiro teste de lentidão falhou com `500`. O read timeout estourou **lendo o corpo** e o Spring embrulhou isso numa `RestClientException` genérica (não em `ResourceAccessException`). A correção foi olhar a **causa raiz**: conectou e travou depois → ambíguo → `202 PENDENTE`. Exemplo de por que o teste de integração pega o que o mock de unidade esconde.

## Arquivos

```
pom.xml                       dependências (Spring Boot 3.3.5, Resilience4j 2.2.0, SpringDoc 2.6.0, WireMock 3.9.2)
src/main/resources/application.yml   timeouts, circuit breaker, bulkhead, threads virtuais
src/main/java/br/com/ada/pagamentos/
  PagamentosApplication.java   ponto de entrada (o "main")
  ParceiroProperties.java      configuração do parceiro (URL, timeouts)
  Dtos.java                    records: PagamentoRequest/Response, CobrancaParceiroRequest (com idempotencyKey)
  ParceiroClient.java          RestClient + @CircuitBreaker + @Bulkhead; classifica erro (conexão x timeout x HTTP)
  PagamentoService.java        regra: idempotência, fallback PENDENTE, liberar chave
  IdempotenciaStore.java       chaves já vistas (em memória)
  ContingenciaStore.java       fallback: guarda PENDENTE (pode ser "desligado" no teste)
  PagamentoController.java     POST /pagamentos + anotações OpenAPI com todos os códigos
  TratadorGlobalDeErros.java   @RestControllerAdvice -> Problem Details
  Excecoes.java                exceções de integração
src/test/java/.../ResilienciaIntegrationTest.java  14 testes com WireMock + MockMvc
saida-execucao.txt            saída real dos testes e da app rodando
```

## Como rodar

Pré-requisitos: JDK 21 e Maven 3.9 (`sudo apt install openjdk-21-jdk-headless maven`).

```bash
mvn test                                   # roda os 14 testes (WireMock sobe sozinho)
mvn spring-boot:run                        # sobe a API em http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html   | contrato: /v3/api-docs
curl -i -X POST localhost:8080/pagamentos -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: 123e4567-e89b-12d3-a456-426614174000' -d '{"id":"pg-1","valor":100.00}'
```

Sem parceiro de verdade em `localhost:9999`, a app responde `503` (conexão recusada) e, depois de 4 falhas, `503` de **circuito aberto**. Para brincar com o httpbin (dica do enunciado), troque `parceiro.base-url`; note que o caminho usado é `/cobrancas`.

## Resultados reais (14/10/2026, horário de Brasília)

`mvn test`: **14 testes, 0 falhas, BUILD SUCCESS** (Java 21.0.12, Maven 3.9.9). Destaques do log:
- parceiro com **3 s** de atraso → nossa API respondeu em **1015 ms** com `202 PENDENTE` (cortou no read timeout).
- circuito **OPEN** → nossa API respondeu em **2 ms** com `503` + `Retry-After: 2`, sem nova chamada ao parceiro.

| Teste | Prova |
|---|---|
| sucesso_devolve201_eEnviaIdempotencyKeyNoCabecalho | 201 e o WireMock recebeu a chave no cabeçalho |
| semIdempotencyKey_devolve400ProblemDetails_eNaoChamaParceiro | 400 `application/problem+json`, parceiro não chamado |
| parceiroLento3s_cortaNoReadTimeout_eDevolve202Pendente | resposta < 2 s, 202 PENDENTE |
| conexaoRecusada_devolve503ComRetryAfter_porqueNadaFoiCobrado | connect ≠ read: 503 em vez de 202 |
| erro500DoParceiro_eAmbiguo_devolve202Pendente_nuncaAprovado | 500 é ambíguo → PENDENTE |
| erro429DoParceiro_devolve503ProblemDetails_naoPropaga429 | 429 vira 503 padronizado |
| erros5xxIntermitentes_respostasPadronizadas | 503 e depois 201 com a **mesma** chave |
| sequenciaDeFalhas_abreOCircuito | estado `OPEN` após 4 falhas |
| circuitoAberto_respondeNaHora503_semChamarParceiro | < 200 ms, contagem do WireMock não muda |
| recuperacao_OPEN_HALFOPEN_CLOSED | OPEN → (2 s) → HALF_OPEN → CLOSED, respostas 201 |
| reenvioAposTimeout_devolveMesma202_semNovaChamada | mesmo 202, parceiro chamado 1 vez |
| mesmaChaveComOutroCorpo_devolve422 | 422 |
| duasRequisicoesSimultaneasMesmaChave_umaVence_outraRecebe409 | uma 201, outra 409, 1 chamada |
| contingenciaTambemFalha_devolve500ProblemDetails_semStackTrace | 500 Problem Details, sem "Exception" no corpo |

Também subimos a app de verdade (`java -jar`) e chamamos com `curl`: 4×`503 parceiro-sem-conexao`, depois `503 circuito-aberto`; o `/v3/api-docs` lista 201/202/400/409/422/500/503. Tudo em `saida-execucao.txt`.

## O que ficou de fora (honestamente)

- **Variação A, teste de carga** (k6/locust): as threads virtuais estão **ligadas**, mas não rodei o teste de carga.
- **Variação B (WebFlux)**: não feita; o design da aula escolheu MVC + threads virtuais.
- **Reconciliação** do `PENDENTE` e **banco real** (`UNIQUE`): simulados em memória.
- **Retry** do Resilience4j: não usado, de propósito (ver decisões).
