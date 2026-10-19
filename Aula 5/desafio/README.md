# Aula 5: desafio de código (Containers, CI/CD e migração sem downtime)

Projeto Spring Boot 4.1 mínimo, equivalente ao spring-petclinic do guia (o GitHub não é acessível da máquina onde foi validado), focado no cenário do checkout: evoluir `status_pagamento` para `estado_saga` sem quebrar a versão anterior.

| Arquivo | O que faz |
| :--- | :--- |
| `Dockerfile` | Multi-stage: JDK 21 + Maven no build; runtime `gcr.io/distroless/java21-debian12:nonroot` com as camadas do Spring Boot, sem shell e sem root. |
| `.dockerignore` | Mantém `target/`, `.git/` e docs fora do contexto de build. |
| `src/main/resources/db/migration/V1..V4` | V1 schema, V2 dados, V3 **expand** (coluna nova nullable), V4 **backfill** idempotente que separa `CANCELADO` em `CANCELADO_SEM_COBRANCA`, `CANCELADO_COM_ESTORNO` e `ESTORNO_EM_REVISAO`. |
| `src/main/resources/db/contract/V5` | **Contract** (`DROP COLUMN status_pagamento`), fora do caminho do Flyway de propósito: só numa release futura. |
| `src/main/java/...` | `PedidoRepository` (v2: lê a coluna nova, grava as duas, transição condicional), `PedidoRepositoryLegado` (simula a v1.5), controller e enum de estados. |
| `src/test/java/...` | 8 testes: só V1 a V4 aplicadas e coluna antiga presente; separação dos cancelados e nenhum nulo/divergência; backfill reexecutado altera 0 linhas; v1.5 e v2 coexistem e o rollback funciona; evento velho/repetido não muda o estado (exatamente uma transição); checksum do Flyway barra V1 alterada; guardrail sem DROP/RENAME/TYPE em `db/migration`; smoke da aplicação (readiness 200, pedido migrado, 404). |
| `scripts/smoke-test.sh` | Smoke test da **imagem**: sobe com `--read-only`, espera readiness 200, consulta um pedido, e falha se houver shell, se rodar como root ou se passar de 250 MB. |
| `.github/workflows/ci.yml` | Testes, SBOM, build da imagem, smoke test e Trivy (falha em CRITICAL). Precisa ser copiado para `.github/workflows/` na raiz para executar. |

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
- Trivy: a primeira versão (Spring Boot 3.3.5) **foi barrada** pelo gate (16 CVEs críticas em Tomcat e Spring); depois de subir para Spring Boot 4.1.1, ainda havia 6 no Tomcat 11.0.24; com `tomcat.version` 11.0.25: **0 CRITICAL**, exit 0. Sistema operacional (debian 12.13 da distroless): 0.
- Não validado: PostgreSQL real e `lock_timeout` (os testes usam H2 em modo PostgreSQL); execução do workflow no GitHub Actions.
