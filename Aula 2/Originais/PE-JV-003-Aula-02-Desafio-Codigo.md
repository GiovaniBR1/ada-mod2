# Desafio Prático (Opcional)

> **Nota:** Esta é uma trilha opcional para aplicar os conceitos de resiliência e Spring Boot. Ela não faz parte da avaliação obrigatória.

## O Desafio: Falha no Parceiro de Pagamentos e Testes de Resiliência

Você deve projetar e implementar um serviço de integração para um provedor de pagamentos. Seu objetivo não é apenas realizar a chamada HTTP, mas garantir que sua aplicação sobreviva caso o provedor fique instável (latência extrema ou erros 500).

### Requisitos da Solução

1. **Cliente HTTP:** Escolha e implemente um cliente HTTP para realizar a integração com a API de pagamentos. Você pode optar pelo declarativo `Spring Cloud OpenFeign`, pelo moderno `RestClient`, pelo clássico `RestTemplate`, ou qualquer outro de sua preferência. A decisão de design é sua.
2. **Idempotência:** A comunicação deve garantir idempotência. Projete os objetos de requisição (usando `Records` do Java) de forma que uma `idempotencyKey` seja sempre mapeada nos cabeçalhos (headers) da chamada.
3. **Resiliência:** Configure as regras de proteção com **Resilience4j**. Sua aplicação deve abrir o circuito rapidamente se o parceiro falhar. Além disso, você deve escolher e justificar uma estratégia para blindar a aplicação contra chamadas absurdamente lentas (ex: `ReadTimeout` nativo do seu cliente HTTP, ou `TimeLimiter`/`Bulkhead` do Resilience4j).
4. **Fallback e Documentação:** Caso o serviço de pagamento esteja indisponível, o sistema deve assumir um fluxo de contingência/fallback (ex: enfileiramento ou aviso de indisponibilidade). Documente o contrato e os códigos HTTP de retorno desse cenário usando as anotações do **Swagger/OpenAPI** (SpringDoc).
5. **Tratamento de Erros Global:** Capture exceções de integração e do Resilience4j (como a `CallNotPermittedException`) usando um `@RestControllerAdvice` para devolver respostas estruturadas ao consumidor (ex: RFC 7807 - Problem Details) sem expor stack traces.
6. **Modelo de Dados Mínimo:** Não se preocupe com um modelo de domínio complexo. Use o mínimo de campos de entrada e saída necessário para atender ao escopo (ex: apenas `id`, `valor` e `idempotencyKey` no envio, e `id` e `status` na resposta). O foco do desafio é a engenharia da resiliência, não a modelagem de negócio.

### Teste de Integração (A Engenharia do Caos Local)

Em vez de depender de uma API instável, valide sua arquitetura simulando cenários de caos. Suba um servidor de simulação com **WireMock** ou **MockServer** no seu ambiente de testes (`@SpringBootTest`) e programe *stubs* para a rota de pagamentos simulando atrasos e erros.

Escreva testes de integração que disparem chamadas ao endpoint da sua própria API (via `MockMvc` ou `TestRestTemplate`) e validem as **respostas HTTP e o comportamento da sua API**:
* **Comportamento sob lentidão:** quando o parceiro demorar (ex: 3s), sua API corta a chamada no tempo limite e devolve a resposta definida (ex: fallback com `202 Accepted`, ou `504 Gateway Timeout`).
* **Abertura de circuito:** uma sequência de falhas força o Circuit Breaker a mudar para `OPEN`.
* **Comportamento com circuito OPEN:** chamadas subsequentes não batem no parceiro e recebem a resposta da sua API imediatamente (ex: `503 Service Unavailable` com header `Retry-After`, ou resposta instantânea de fallback), sem prender conexões esperando timeout.

**Vá além do básico (Respostas da nossa API em corner cases e exceções):**
Estresse os testes validando como a sua API responde para o consumidor final em situações extremas:
* **Connect timeout vs. Read timeout:** qual é o status HTTP e payload devolvido pela sua API quando a conexão recusa vs. quando a leitura trava?
* **Erros 5xx intermitentes e HTTP 429 do parceiro:** sua API trata isso devolvendo erro padronizado (Problem Details - RFC 7807) ou propaga status inadequados?
* **Transição de estados (`OPEN` -> `HALF-OPEN` -> `CLOSED`):** o que a sua API devolve durante a recuperação do parceiro?
* **Idempotência pós-timeout:** se o cliente reenviar a mesma requisição após um timeout anterior, qual status e resposta ele recebe da sua API (ex: 200, 409 Conflict, resposta cacheada)?
* **Falha no fallback:** se a sua contingência (ex: mensageria ou banco) também falhar, qual status final a sua API devolve ao usuário?

---

> 💡 **Dicas Práticas**
> - **Simulação Rápida:** Antes de subir o WireMock ou MockServer nos testes automatizados, você pode apontar a URL do seu cliente HTTP localmente para o [httpbin.org](https://httpbin.org) (ex: chamando `/delay/3` ou `/status/500`) para visualizar rapidamente o comportamento do Circuit Breaker em tempo de execução.

---

## Extensão do Desafio: Escalando com Virtual Threads e WebFlux

A arquitetura e as garantias de resiliência do Spring mudam bastante dependendo do modelo de concorrência. Para materializar as discussões da aula teórica, considere criar duas ramificações da sua solução:

### Variação A: Tomcat com Virtual Threads (Loom)
Mantenha o seu código de integração bloqueante, mas ative as Virtual Threads do Java (Loom) no `application.yml`. Realize um pequeno teste de carga (usando `k6`,`locust` ou `jmeter`) e monitore os logs para observar que a aplicação suporta a rajada de I/O bloqueado do servidor mockado sem esgotar as threads nativas do sistema operacional.

### Variação B: Abordagem Reativa (WebFlux)
Abandone o seu cliente síncrono e o Servlet API. Migre para o ecossistema reativo com **Spring WebFlux** e utilize o `WebClient`. Refatore a solução para que o pipeline seja 100% não-bloqueante e observe como operadores nativos (como o `.timeout()`) simplificam o fluxo em comparação com as anotações do Resilience4j tradicionais.
