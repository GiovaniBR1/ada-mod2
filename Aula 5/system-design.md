# System Design: Aula 05 - Containers, CI/CD & Deploy sem Downtime

> **Cenário:** o pipeline recompilou a `main`, sobrescreveu `checkout:latest` e, no boot, o Flyway renomeou `status_pagamento` para `estado_saga`. Flyway: a ferramenta que muda a tabela. Os 80% que liam a coluna antiga quebraram. A imagem velha tinha sumido. O banco já estava mudado. A carga é 5.000 pedidos/s, várias réplicas, um PostgreSQL.

Detalhe: [anexo](anexo-detalhes.md). Estados: [apêndice](../Aula%204/apendice-maquina-de-estados.md).

## 1. Empacotamento, Superfície de Ataque e Promoção Imutável

### 1.1 Anatomia da Imagem e Redução de Superfície de Ataque

- **Construção vs Execução:**
  > A escolha é dois estágios no mesmo Dockerfile. Só o segundo sobe.
  > Compila com `eclipse-temurin:21-jdk-jammy` e `mvn -B package`. `jarmode=tools extract --layers` (separa bibliotecas e o nosso código).
  > Executa em `gcr.io/distroless/java21-debian13:nonroot`, presa ao digest. Distroless: imagem sem shell. Digest: a impressão digital. Não se sobrescreve.
  > O processo é `USER nonroot` (uid 65532), disco só de leitura, `/tmp` num `emptyDir` (disco temporário do pod).
  > O `pom.xml` usa Spring Boot 4.1.1 e fixa `<tomcat.version>11.0.25</tomcat.version>`. O gate recusa CVE crítica que já tem correção publicada.
  > Sem `sh`, o diagnóstico é log, métrica ou `kubectl debug`. [Imagem](anexo-detalhes.md#imagem).

### 1.2 Rastreabilidade, Identidade do Artefato e Parametrização

- **Da Homologação à Produção:**
  > A escolha é um build no merge. A promoção só troca o digest.
  > A versão `1.5.0` vem do `pom.xml`. A tag `checkout:1.5.0-<commit>` é imutável. O ambiente aponta para `sha256`.
  > No incidente, `latest` foi sobrescrita e o rollback puxou o binário novo. O registry guarda as últimas 10 versões de produção. O admission (regra do cluster que aceita ou recusa o pod) recusa imagem citada por tag.
  > O `application.yml` só lê `SPRING_DATASOURCE_URL`, `PIX_PSP_URL` e `SPRING_PROFILES_ACTIVE`. Isso é config fora do binário (fator III). Senha e HMAC vêm de Secret, por pull request. HMAC: uma assinatura. Sem a chave, não dá para forjar.
  > Forma, não um digest real: `registry/checkout@sha256:9b2e7c...f41a`. O rollback reverte o commit do repositório de deploy. As etiquetas OCI aparecem no `/actuator/info`. [Promoção](anexo-detalhes.md#promocao).

## 2. Estratégia de Rollout e Migração de Banco sem Downtime

### 2.1 O Modelo de Rollout em Produção

- **A Escolha:** (X) Canary Release (Liberação Gradual por Percentual)  ( ) Blue-Green Deployment (Alternância de Ambientes)
- **Justificativa e Mitigação das Desvantagens do seu Modelo:**
  > A escolha é canary. Canary: a versão nova recebe uma fatia do tráfego.
  > Degraus: 1% (~50 TPS, 10 min), 5% (~250 TPS, 10 min), 20% (~1.000 TPS, 15 min), 50% (~2.500 TPS, 15 min), 100%. Só avança com readiness 200.
  > Blue-green dobraria máquina no pico e viraria 100% num clique. O banco continuaria um só.
  > Duas leituras ruins abortam. HTTP 5xx da nova: até 0,5% e até 1,5 vez a estável. p99 não sobe mais de 20%. Zero erro de coluna. Pool abaixo de 80%. No negócio: nasce `CONCLUIDO`, parado além do prazo dispara, e não há `CONCLUIDO` sem reserva.
  > Webhook da estável pode cair na nova. As duas conferem o HMAC e só mudam a linha se status ainda é `AGUARDANDO_CAPTURA` ou `EXPIRADO`. A estável segue pronta para 5.000 TPS.
  > A flag `saga_estados_v2` fica só nos pods v2. Ligada, a v2 grava os doze estados em `estado_saga` e o token antigo em `status_pagamento`. A v1.5 continua lendo `status_pagamento`. O Argo Rollouts (a ferramenta que move a porcentagem do canary) já opera em 1% e 5%, com os mesmos sinais. [Canary](anexo-detalhes.md#canary).

### 2.2 Evolução de Schema Sem Downtime e Plano de Rollback

- **Passo a Passo da Migração:**
  > `pedidos.status` da Aula 4 é o `status_pagamento` deste enunciado. A coluna nova, ao lado, é `estado_saga`.
  > A escolha é expand/contract em três releases. Primeiro acrescenta a coluna. As duas versões convivem. Só depois a antiga sai.
  > A v1 do enunciado só lê a coluna antiga e fica no ar no expand. A v1.5 lê `status_pagamento` e grava as duas. A v2 lê `estado_saga` e também grava as duas. O contract (v2.1, `V12`) tira a antiga fora do pico.
  > A migração é um Job, com usuário próprio, `lock_timeout` de 3 s e `spring.flyway.enabled=false`. A aplicação não tem `ALTER`.
  > `V10` acrescenta `estado_saga` nula. `V11` copia em lotes de 5.000. O backfill roda no banco de cada dono. O Checkout só lê `pedidos`. O Pagamento classifica `CANCELADO` e devolve `(order_id, destino)`.
  > `AGUARDANDO_PIX` vira `AGUARDANDO_PAGAMENTO`. `CONFIRMADO` e `CONCLUIDO` são o par que muda de nome. `PAGO` é o mesmo token nas duas colunas. A escrita viva da v1.5 grava `estado_saga = CONCLUIDO` e `status_pagamento = CONFIRMADO`.
  > O H2, no `V4`, ainda copia `CONFIRMADO`, porque o teste espera esse token. Antes de seguir: zero nulos. [Migração](anexo-detalhes.md#migracao).
- **Garantia de Rollback Imediato:**
  > A volta é para a v1.5, não para a v1 do enunciado. Nenhuma migração é desfeita.
  > A v1 já saiu antes do canary. A v1.5 ficou no ar, ainda lê `status_pagamento` e já cabe 5.000 TPS. A v2 gravou as duas colunas.
  > Com ~1.000 TPS na v2 (20%), duas leituras ruins devolvem 100% à v1.5.
  > Exemplo: p99 da v2 de 180 ms para 900 ms. A v1.5 segue em 175 ms. `kubectl argo rollouts abort checkout` devolve o peso. A v2 termina o que está em andamento (até 30 s) e para o Kafka.
  > `CONCLUIDO` continua legível nas duas colunas. `CANCELADO_COM_ESTORNO` convive com `CANCELADO`. [Rollback](anexo-detalhes.md#rollback).

## 3. Guardrails de Segurança, Governança e Validação da Release

### 3.1 Gates de Segurança e Guardrails no Processo de Entrega

- **Guardrails do Commit ao Deploy:**
  > O `RENAME` de `status_pagamento` para no pull request. Entram `mvn -B verify` (JDK 21), gitleaks, CodeQL, hadolint e `flyway validate`.
  > `DROP`, `RENAME` ou `ALTER ... TYPE` reprovam, salvo contract que prove que produção já não lê a coluna. `db/migration/` exige o time de dados. A `main` recusa push direto.
  > No merge, um build: `cosign verify`, SBOM (a lista do que entrou na imagem) e `trivy image --exit-code 1 --severity CRITICAL --ignore-unfixed`. CVE alta alerta e não bloqueia. A imagem sai assinada, com tag imutável.
  > O admission recusa imagem sem assinatura, por tag, como root ou sem SBOM. [Gates](anexo-detalhes.md#gates).
- **Governança e Controle em Produção:**
  > Produção só muda por GitOps: o cluster aplica o git. O Argo CD desfaz divergência. Não há `kubectl apply` pessoal. Há promoção de um digest.
  > A esteira usa OIDC que expira com a execução. A aplicação não tem `ALTER`. Hotfix usa os mesmos gates, uma revisão do plantão e degraus mais curtos.
  > Break-glass (acesso de emergência, fora do caminho normal) dura 1 hora, com justificativa e auditoria. O que foi feito vira pull request. [Governança](anexo-detalhes.md#governanca).

### 3.2 Estratégia de Testes da Imagem e da Migração

- **Validação Pré-Tráfego:** teste de jar não prova que a imagem sobe nem que as duas versões convivem.
- **Plano de Testes na Pipeline:**
  > Antes do tráfego, a esteira sobe as duas imagens no mesmo banco.
  > Smoke: digest, usuário não root, disco só leitura, `/tmp` gravável. `docker run --entrypoint sh` falha. O processo não é o uid 0. `scripts/smoke-test.sh` imprime `SMOKE OK`.
  > JUnit `MigracaoExpandContractTest`: falha se houver `DROP` ou `RENAME`. Mantém as duas colunas e confere o backfill do H2. Não executa a saga.
  > Plano da saga, fora desse JUnit: estoque esgotado termina `CANCELADO_SEM_COBRANCA`, com zero cobrança. Reserva expirada termina `CANCELADO_COM_ESTORNO`, com uma devolução. A exceção do Pix atrasado está na [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento).
  > O H2 não prova `ALTER` abaixo de 1 s em produção. Esse portão está marcado como não executado. [Testes](anexo-detalhes.md#testes).
