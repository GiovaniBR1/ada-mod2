# Aula 08 (PE-JV-003) — Engenharia de Software com IA & Agentes: perguntas e respostas

**Tese: o modelo propõe, o backend decide.** O LLM (modelo de linguagem) redige, classifica e sugere; regras, valores e permissões ficam em código determinístico.

Formato: **PERGUNTA** → resposta recuada. Fontes no fim.

---

## A) Sala de Aula Invertida — Pesquisa

**1. SDD: por que prompts soltos falham? Como specs formais e schemas JSON reduzem alucinação e direcionam agentes?**
> **Prompt solto não carrega as regras do projeto; spec formal sim.**
> - SDD (Spec-Driven Development) parte de uma especificação estruturada, quebrada em desenho e tarefas.
> - Sem spec, o agente gera classe que compila, mas viola invariante de domínio ou convenção.
> - Schema JSON (contrato de campos, tipos, obrigatórios) limita o que o modelo pode produzir e valida por código.
> - Ex.: no Spring AI, parâmetro marcado `required` que o modelo não sabe preencher será inventado; declare só o que ele conhece.
> - Ressalva: spec longa demais fica difícil de revisar (Thoughtworks, nível "Assess").

**2. Código vs Modelo: o que fica determinístico no backend Java e o que pode ir ao LLM?**
> **Regra, valor, permissão e efeito colateral ficam no código; texto e triagem podem ir ao LLM.**
> - Determinístico (mesma entrada, mesma saída): cálculo de valor, aprovação, estorno, idempotência, autorização.
> - Delegável: redigir justificativa, resumir chamado, classificar categoria, sugerir próxima ação.
> - O modelo só *pede* uma ferramenta; quem executa e valida é a aplicação (Spring AI).
> - Ex.: o LLM sugere "estorno"; o backend confere dono, saldo e status antes de agir.

**3. MCP: o que é, que problema resolve, e por que não se confunde com rodar modelos locais?**
> **MCP (Model Context Protocol) é um padrão aberto para ligar aplicações de IA a ferramentas e dados.**
> - Resolve o "N×M": cada app e cada ferramenta precisavam de integração própria; com MCP, escreve-se uma vez.
> - Usa JSON-RPC entre host/cliente e servidor; o servidor expõe *tools*, *resources* e *prompts*.
> - Não roda modelo: é só o "cabo USB-C" entre o app e as ferramentas. O modelo pode estar na nuvem ou local.
> - Ex.: um servidor MCP expõe `consultar_pedido(id)` com schema de entrada; qualquer cliente compatível o usa.

**4. Privacidade e OWASP LLM: como sanitizar PII antes do prompt? Como barrar prompt injection direta e indireta?**
> **Mascare PII antes de montar o prompt (e antes do log); trate todo texto externo como dado não confiável.**
> - PII (dado pessoal): detecte CPF (regex + dígitos verificadores), e-mail, telefone; nomes pedem NER (reconhecimento de entidades). Troque por `[CPF_1]`.
> - Injection *direta*: o usuário escreve a instrução maliciosa. *Indireta*: ela vem em página, arquivo ou documento do RAG.
> - Não existe defesa infalível: limite privilégios, valide saída por código, separe conteúdo externo e exija aprovação humana em ação de risco.
> - OWASP 2026: LLM01 Prompt Injection, LLM02 Sensitive Information Disclosure, LLM03 Excessive Agency (em 2025 era LLM06).

**5. Evals: como testar clientes de IA com dublês determinísticos e medir a precisão de recuperação no RAG?**
> **Dois testes diferentes: montagem do prompt (dublê, todo build) e acurácia (conjunto fixo, métrica, periódico).**
> - *Teste determinístico*: mock do cliente de IA; afirma que o prompt não tem CPF, que o schema vai junto e que resposta inválida cai no fallback.
> - *Eval (avaliação contínua)*: conjunto fixo de casos com resposta esperada; mede taxa de acerto e acompanha a regressão. LLM-as-judge (outro modelo julgando) só apoia.
> - RAG (busca de trechos para o prompt): monte perguntas com os trechos corretos esperados.
> - **precision@k**: dos k trechos devolvidos, quantos são relevantes. **recall@k**: dos relevantes, quantos vieram nos k.
> - **hit rate**: % de perguntas com ao menos 1 relevante nos k. **MRR**: média de 1/posição do primeiro relevante.

---

## B) Desafio Arquitetural: Engenharia de Harness

> *Harness* = tudo ao redor do modelo: contexto, ferramentas, regras, testes e limites onde o agente trabalha.

### B1. "Construir a coisa que constrói a coisa"

**B1a. Por que o papel migrou de digitar código para desenhar e calibrar o harness?**
> **Porque o gargalo deixou de ser digitar e passou a ser dar ao agente ambiente, regras e verificação.**
> - O time da OpenAI relata que o progresso inicial foi lento por ambiente subespecificado, não por incapacidade do modelo.
> - Quando falhava, a pergunta era: "que capacidade falta, e como torná-la legível e verificável para o agente?"
> - Corrigir o gerador vale mais que corrigir só a saída: a regra entra no harness e vale para as próximas execuções.

**B1b. O que acontece com prompts soltos em vez de SDD e guardrails no repositório?**
> **O agente erra de formas diferentes a cada vez, e o sênior vira caçador de erro nos PRs.**
> - Sem spec, sem teste e sem lint, nada barra invariante quebrado nem método inventado.
> - Resultado: PRs grandes, fadiga de revisão e débito técnico acumulado (cenário da aula).
> - Com SDD e guardrails, o erro é barrado cedo, por máquina, antes de chegar à pessoa.

### B2. Anatomia do harness e feedback loops

**B2a. Quais peças formam um harness confiável no ecossistema backend?**
> **Contratos, contexto curto, ferramentas com escopo mínimo e verificações automáticas.**
> - Contratos: OpenAPI e schemas JSON/DB, tipados e validados na borda.
> - Contexto: `AGENTS.md` curto apontando para `docs/` (mapa, não enciclopédia); specs e planos versionados.
> - Ferramentas: MCP/CLI com permissão mínima (rodar teste, ler schema).
> - Verificação: linters, testes de arquitetura (ArchUnit), checagem de schema, CI.

**B2b. Como desenhar loops determinísticos e rápidos para o agente rodar testes, detectar quebra e se autocorrigir antes do PR?**
> **Dê ao agente um comando único e rápido que falhe com mensagem clara, e o faça rodar até passar.**
> - Ordem do mais barato ao mais caro: compilar → lint → testes unitários → contrato/schema → integração.
> - Mensagem de erro com instrução de correção entra no contexto do agente (prática da OpenAI em lints próprios).
> - Anthropic: o agente precisa de "verdade do ambiente" a cada passo (resultado de teste) e de limite de iterações.
> - Ex. (Giovani): o juiz independente é um loop de revisão por agente separado; testes e lint são o loop barato antes dele.

### B3. Manutenção, governança e limites

**B3a. Que riscos o time deve vigiar (poluição de contexto, privilégios excessivos, degradação de regras)?**
> **Os três crescem sozinhos se ninguém poda.**
> - Contexto: arquivo gigante de regras esconde as importantes; vira "cemitério de regras velhas" (OpenAI; Thoughtworks: *agent instruction bloat*).
> - Privilégios: ferramenta com mais funções ou permissões que o necessário (OWASP Excessive Agency).
> - Degradação: regra só em texto envelhece; promova as importantes a lint/teste e revise datas e donos.

**B3b. Em operação transacional crítica, como o harness garante que o código determinístico mantenha o controle?**
> **Fazendo a ferramenta do agente ser um comando validado pelo backend, nunca uma decisão do modelo.**
> - O backend recalcula e checa tudo (dono, valor, estado, idempotência) e ignora o que vier do texto.
> - Testes de contrato e de arquitetura impedem o LLM de ser chamado dentro do caminho de decisão.
> - Ação irreversível ou de alto valor exige aprovação humana (OWASP: *complete mediation* e aprovação).

---

## C) Debate Técnico

**C1. Papel do engenheiro sênior: se agentes escrevem a maior parte do código, qual é o diferencial? O que é construir a coisa que constrói a coisa?**
> **O diferencial é projetar o sistema que produz código correto: especificar, restringir e verificar.**
> - Trabalho novo: priorizar, transformar feedback em critérios de aceite, desenhar contexto, ferramentas e checagens.
> - "Construir a coisa que constrói a coisa" = em vez de consertar cada saída, melhorar o gerador (harness).
> - A responsabilidade continua humana: o modelo não responde por incidente de produção.

**C2. Harness frouxo vs alucinação: a falha raiz está no modelo ou na falta de restrições?**
> **Na maior parte dos casos, na falta de restrição e de verificação; o modelo errará de vez em quando mesmo assim.**
> - Alucinação (resposta plausível e falsa) é esperada; o harness existe para detectá-la.
> - Método inexistente é pego por compilação e teste; invariante quebrado, por teste de domínio e contrato.
> - A OpenAI trata falha do agente como sinal do que falta: ferramenta, guardrail ou documentação.

**C3. Por que feedback loops determinísticos (lint, schema, testes locais) antes da revisão humana?**
> **Porque atenção humana é o recurso mais escasso e a máquina verifica mais barato e mais rápido.**
> - Cada erro óbvio que chega ao PR consome tempo do sênior que deveria revisar arquitetura.
> - Teste e lint dão o mesmo veredito toda vez; o julgamento humano fica para o que exige julgamento.
> - Anthropic: código é bom para agentes porque testes automáticos verificam a solução; revisão humana segue necessária.

**C4. Governança de contexto: o que acontece com a assertividade do agente se o repositório está entupido de regras genéricas e docs defasadas? Como manter sinal alto e ruído baixo?**
> **A assertividade cai: o agente perde regras importantes ou segue regras já falsas.**
> - Contexto é escasso; quando tudo é "importante", nada é (OpenAI). Thoughtworks marca *instruction bloat* como "Caution".
> - Boas práticas: `AGENTS.md` curto como índice, detalhes sob demanda (*progressive disclosure*), um dono por doc.
> - Automatize: CI que valida links e datas, e rotina que abre PR para doc defasada.
> - Regra que importa de verdade vira lint ou teste, não parágrafo.

**C5. Menor privilégio em ferramentas MCP: que barreiras impedem comando destrutivo e vazamento de segredo?**
> **Ferramentas pequenas e específicas, com credencial própria de menor privilégio e aprovação para o que destrói.**
> - Evite ferramenta aberta ("rodar shell", "executar SQL"); prefira `consultar_pedido(id)`.
> - Banco: usuário somente leitura, só nas tabelas necessárias; segredo fica no servidor, nunca no prompt, no log ou no retorno.
> - Spec MCP: servidor deve validar entradas, controlar acesso, limitar taxa e sanitizar saídas; cliente confirma operação sensível e usa timeout.
> - Spring AI: ferramentas destrutivas por chamada (não como padrão) e `McpToolFilter` para limitar o que entra.

**C6. Fronteira transacional: por que o modelo redige a justificativa de uma recusa, mas jamais calcula valor nem decide a aprovação?**
> **Porque o LLM é probabilístico: pode errar ou ser manipulado, e dinheiro exige resultado exato, auditável e repetível.**
> - A decisão sai do backend (regras, limites, antifraude) e vira um código de motivo, como `LIMITE_EXCEDIDO`.
> - O LLM só transforma esse código em texto claro para o cliente; se falhar, usa-se mensagem padrão.
> - Ex. (Giovani): numa saga de checkout com Pix e estorno, o valor e a idempotência vêm do serviço, não de texto gerado.

---

## D) Os três incidentes de produção (5.000 TPS)

**D1. Vazamento de PII: causa e como evitar?**
> **Causa: texto livre com CPF e nome foi ao modelo e aos logs sem mascaramento.**
> - Mascare antes do prompt *e* antes do log, trace e retorno de ferramenta (OWASP 2026: logs e argumentos de tool são superfície de vazamento).
> - Troque por token (`[CPF_1]`); o mapa original fica no backend.
> - Valide a saída antes de exibir; teste com dublê que o prompt montado não contém CPF.

**D2. Injection acionando estorno: causa e como evitar?**
> **Causa: instrução no campo de observação virou chamada de ferramenta, e a ferramenta confiou nela.**
> - A ferramenta de estorno valida no backend (dono, valor, status, política) e ignora qualquer comando vindo do texto.
> - Ferramenta de estorno só por fluxo autenticado do usuário, com aprovação humana acima de um limite; o agente de triagem nem a recebe.
> - Trate observação como dado externo (separado do prompt) e monitore chamadas anormais.

**D3. Degradação de throughput por chamada síncrona à IA (timeouts de 15 s esgotando pools): causa e como evitar?**
> **Causa: uma dependência lenta e externa no caminho crítico segurou recursos.**
> - Pela Lei de Little (simultâneas = taxa × tempo), 5.000 req/s × 15 s ≈ 75.000 requisições abertas.
> - Tire a IA do caminho síncrono: fila/assíncrono, timeout curto (poucos segundos), *circuit breaker* (abre após falhas) e *bulkhead* (pool isolado).
> - Fallback: mensagem padrão por código de motivo. Ex.: Resilience4j (que você usou no checkout) fornece breaker e bulkhead.
> - Mesmo com virtual threads, o limite passa a ser pool de conexões e o provedor.

---

## Fontes

Lidas por inteiro ou nas partes citadas:
- [OpenAI — Harness engineering: leveraging Codex in an agent-first world (11/02/2026)](https://openai.com/index/harness-engineering/) — B1, B2, B3, C1–C4.
- [Anthropic — Building effective agents (19/12/2024)](https://www.anthropic.com/engineering/building-effective-agents) — workflows vs agentes, verdade do ambiente, testes, ferramentas.
- [MCP — Especificação (versão 2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25) e [Tools (latest)](https://modelcontextprotocol.io/specification/latest/server/tools) — A3, C5.
- [MCP — O que é o MCP?](https://modelcontextprotocol.io/docs/getting-started/intro) — A3.
- [Thoughtworks Radar — Spec-driven development (Assess, nov/2025)](https://www.thoughtworks.com/en-us/radar/techniques/spec-driven-development) — A1.
- [Thoughtworks Radar — Agent instruction bloat (Caution, abr/2026)](https://www.thoughtworks.com/radar/techniques/agent-instruction-bloat) — B3, C4.
- [Spring AI — Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html), [Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html), [Evaluation Testing](https://docs.spring.io/spring-ai/reference/api/testing.html) — A1, A2, A5, C5.
- [OWASP Top 10 for LLM Applications 2026 (publicada em 04/08/2026, versão vigente)](https://genai.owasp.org/resource/owasp-genai-llm-top-10-2026/) e [texto-fonte LLM01, LLM02, LLM03](https://github.com/GenAI-Security-Project/GenAI-LLM-Top10/tree/main/2026/final).
- [OWASP LLM 2025 (anterior): lista](https://genai.owasp.org/llm-top-10/), [LLM01](https://genai.owasp.org/llmrisk/llm01-prompt-injection/), [LLM02](https://genai.owasp.org/llmrisk/llm022025-sensitive-information-disclosure/), [LLM06 Excessive Agency](https://genai.owasp.org/llmrisk/llm062025-excessive-agency/).

Notas de confiança:
- O PDF não traz URLs legíveis; os links acima são as fontes oficiais equivalentes aos seis títulos da aula.
- A frase "construir a coisa que constrói a coisa" aparece, nas buscas, atribuída a Patrick Debois (Tessl; [AI Engineer](https://ai.engineer/talks/zCJtYuqwm7E-coding-agents-dont-scale-themselves-neither-do)); o artigo de "harness engineering" mais próximo é o da OpenAI.
- Precision@k, recall@k, hit rate, MRR, regex de CPF/NER e Lei de Little são conhecimento geral, não vêm das fontes acima.
