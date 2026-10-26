# Aula 5: desafio de código (Containers, CI/CD e migração sem downtime)

Projeto Spring Boot 4.1 mínimo, equivalente ao spring-petclinic do guia (o GitHub não é acessível da máquina onde foi validado), focado no cenário do checkout: evoluir `status_pagamento` para `estado_saga` sem quebrar a versão anterior.

| Arquivo | O que faz |
| :--- | :--- |
| `Dockerfile` | Multi-stage: JDK 21 + Maven no build; runtime `gcr.io/distroless/java21-debian13:nonroot` fixada por digest com as camadas do Spring Boot, sem shell e sem root. |
| `.dockerignore` | Mantém `target/`, `.git/` e docs fora do contexto de build. |
| `src/main/resources/db/migration/V1, V3, V4` e `src/test/resources/db/migration/V2` | V1 schema; V2 dados de exemplo **só nos testes** (o Spring Boot não empacota `src/test`, então a imagem não leva dados falsos); V3 **expand** (coluna nova nullable), V4 **backfill** idempotente que separa `CANCELADO` em `CANCELADO_SEM_COBRANCA`, `CANCELADO_COM_ESTORNO` e `ESTORNO_EM_REVISAO`. |
| `src/main/resources/db/contract/V5` | **Contract** (`DROP COLUMN status_pagamento`), fora do caminho do Flyway de propósito: só numa release futura. |
| `src/main/java/...` | `PedidoRepository` (v2: lê a coluna nova, grava as duas, transição condicional), `PedidoRepositoryLegado` (simula a v1.5), controller e enum de estados. |
| `src/test/java/...` | 8 testes: só V1 a V4 aplicadas e coluna antiga presente; separação dos cancelados e nenhum nulo/divergência; backfill reexecutado altera 0 linhas; v1.5 e v2 coexistem e o rollback funciona; evento velho/repetido não muda o estado (exatamente uma transição); checksum do Flyway barra V1 alterada; guardrail sem DROP/RENAME/TYPE em `db/migration`; smoke da aplicação (readiness 200, pedido migrado, 404). |
| `scripts/smoke-test.sh` | Smoke test da **imagem**: sobe com `--read-only`, espera readiness 200, consulta um pedido, consulta a rota de pedidos (404 para pedido inexistente) e falha se houver shell, se rodar como root ou se passar de 250 MB. |
| `.github/workflows/ci.yml` | Testes, SBOM do Maven, `cosign verify` da base distroless, build da imagem, smoke test, Trivy de vulnerabilidades (falha em CRITICAL), Trivy da configuração da imagem (root, segredo em ENV) e SBOM da imagem inteira. Precisa ser copiado para `.github/workflows/` na raiz para executar. |

## Como rodar

```bash
mvn -B verify
mvn -B cyclonedx:makeAggregateBom
docker build -t checkout:1.5.0 .
scripts/smoke-test.sh checkout:1.5.0
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy image --exit-code 1 --severity CRITICAL --ignore-unfixed checkout:1.5.0
```

## Resultado da validação (19/10/2026)

- `mvn -B verify`: BUILD SUCCESS, 8 testes, 0 falhas (OpenJDK 21.0.12, Maven 3.9.9).
- `docker build`: ok; imagem de 221 MB (a base distroless java21 sozinha tem 192 MB, então a meta de 120 MB do material não é alcançável com essa base sem `jlink`).
- `scripts/smoke-test.sh`: SMOKE OK.
- Trivy: o gate falha em CVE crítica com correção publicada (`--severity CRITICAL --ignore-unfixed`). O `pom.xml` usa Spring Boot 4.1.1 e fixa `tomcat.version` em 11.0.25. Contagens fechadas de um scan antigo não são repetidas aqui, porque o relatório desse scan não está no repositório.
- Revisão de 19/10 (referências da aula): base trocada para distroless Debian 13 por digest (`gcr.io/distroless/java21-debian13:nonroot`), seed movido para os testes, gate de configuração da imagem. `mvn -B verify` 8/8; SMOKE OK. CVE alta gera alerta e não bloqueia o gate.
- Para chegar perto de 120 MB: JRE mínima com `jlink` (no Paketo, `BP_JVM_JLINK_ENABLED=true`).
- Não validado: PostgreSQL real e `lock_timeout` (os testes usam H2 em modo PostgreSQL); execução do workflow no GitHub Actions.
