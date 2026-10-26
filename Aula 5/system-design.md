# Template de System Design: Aula 05 - Containers, CI/CD & Deploy sem Downtime

> **Contexto do Cenário: O Deploy que Quebrou o Checkout e o Rollback Impossível**
>
> Sua equipe está reestruturando a engenharia de release do serviço de **Checkout** após um incidente severo na Black Friday:
>
> 1. **O Incidente:** Para subir uma otimização urgente na Saga, o pipeline de produção recompilou a branch `main`, sobrescreveu a tag `checkout:latest` e iniciou a atualização. No boot da primeira instância nova, o **Flyway** renomeou a coluna `status_pagamento` para `estado_saga`.
> 2. **O Efeito em Cadeia:** Os 80% de servidores que ainda rodavam a versão anterior passaram a falhar imediatamente (`column "status_pagamento" does not exist`). Ao tentar acionar o rollback puxando `:latest`, a equipe descobriu que a imagem anterior havia sido sobrescrita e, mesmo restaurando um backup do binário antigo, ele continuou falhando porque o banco já estava alterado.
> 3. **A Carga:** 5.000 TPS ativos com múltiplas réplicas de aplicação conectadas ao mesmo banco PostgreSQL.
>
> **Instruções:** Responda às decisões arquiteturais abaixo fundamentando suas escolhas estritamente com base nos requisitos e restrições deste cenário. Documente suas decisões no repositório contínuo do seu projeto através de commits atômicos e bem descritivos.

SQL, degraus do canary, gates e o custo de cada escolha estão no [anexo](anexo-detalhes.md). Os estados do pedido são os do [apêndice da Aula 4](../Aula%204/apendice-maquina-de-estados.md).

---

## 1. Empacotamento, Superfície de Ataque e Promoção Imutável

### 1.1 Anatomia da Imagem e Redução de Superfície de Ataque

- **Construção vs Execução:** Como você estruturará o empacotamento do serviço de Checkout para produção? Especifique o que deve ser usado na etapa de compilação, o que deve (e o que não deve) entrar na imagem final de execução, com qual permissão de usuário o processo rodará e qual o trade-off da sua escolha de imagem base para a segurança e para o diagnóstico em produção.
  > Decisão: dois estágios no mesmo Dockerfile; só o segundo vai para o cluster.
  > Compila com `eclipse-temurin:21-jdk-jammy` e `mvn -B package`. `jarmode=tools extract --layers` separa dependências, carregador, dependências de desenvolvimento e o código nosso.
  > Executa em `gcr.io/distroless/java21-debian13:nonroot`, presa ao digest. Sem shell, sem JDK, sem fonte e sem segredo. O processo é `USER nonroot` (uid 65532), com disco só de leitura e `/tmp` num `emptyDir`.
  > Por quê: cada pacote a mais é superfície a mais. O `pom.xml` usa Spring Boot 4.1.1 e fixa o Tomcat em 11.0.25 (18/08/2026), que corrige falhas da 11.0.24. O gate recusa CVE crítica já corrigida. Um scan que não está neste repositório não vira número.
  > Exemplo: sem `sh` para entrar no container. O diagnóstico é log, métrica, trace e, se precisar, `kubectl debug`. Dockerfile, e não Paketo, porque cada estágio precisa aparecer. YAML: [anexo, imagem](anexo-detalhes.md#imagem).

### 1.2 Rastreabilidade, Identidade do Artefato e Parametrização

- **Da Homologação à Produção:** Como sua esteira garante que o binário executado em produção é exatamente o mesmo que passou nos testes de homologação? Explique como as configurações mudam entre ambientes (dev, staging, prod) sem recompilar o artefato e como você identifica a versão no registry para que um rollback nunca puxe um binário sobrescrito por engano.
  > Decisão: um build no merge, e a promoção só troca o digest. A versão `1.5.0` é a do `pom.xml`. A tag `checkout:1.5.0-a1b2c3d` é imutável; o ambiente aponta para `sha256`.
  > Por quê: no incidente a esteira recompilou na hora e `latest` foi sobrescrita, então o rollback puxou o binário novo. Digest não se sobrescreve. O registry guarda as últimas 10 versões que estiveram em produção. O admission recusa imagem referenciada por tag.
  > Configuração, fator III: o `application.yml` só lê `SPRING_DATASOURCE_URL`, `PIX_PSP_URL` e `SPRING_PROFILES_ACTIVE`. Senha e HMAC vêm de Secret, por pull request.
  > Exemplo: staging roda `registry/checkout@sha256:9b2e7c...f41a` (forma do digest, não um valor real). O rollback reverte o commit do repositório de deploy. As etiquetas OCI aparecem no `/actuator/info`. [Anexo, promoção](anexo-detalhes.md#promocao).

---

## 2. Estratégia de Rollout e Migração de Banco sem Downtime

### 2.1 O Modelo de Rollout em Produção

- **A Escolha:** (X) Canary Release (Liberação Gradual por Percentual)  ( ) Blue-Green Deployment (Alternância de Ambientes)
- **Justificativa e Mitigação das Desvantagens do seu Modelo:**
  - *Se você escolheu Canary Release:* Como o tráfego evoluirá sob 5.000 TPS, quais sinais definirão o avanço ou a reversão (rollback) automática e como lidar com o tempo em que duas versões diferentes da API atendem clientes simultaneamente?
  - *Se você escolheu Blue-Green Deployment:* Como você gerenciará o custo de infraestrutura duplicada no pico da Black Friday, o aquecimento das novas instâncias (conexões/cache) antes da virada de chave e o risco de virar 100% do tráfego de uma só vez?
  > Decisão: canary. A versão nova nasce além da frota estável: 1% (~50 TPS, 10 min), 5% (~250 TPS, 10 min), 20% (~1.000 TPS, 15 min), 50% (~2.500 TPS, 15 min), 100%. O Argo Rollouts só avança com readiness 200.
  > Por quê: blue-green dobraria máquina no pico e viraria 100% num clique, e o banco continuaria um só. Duas leituras ruins abortam sozinhas. HTTP 5xx da nova não passa de 0,5% nem de 1,5 vez a estável; p99 não sobe mais de 20%; zero erro de coluna; pool abaixo de 80%. No negócio: `CONCLUIDO`, parado além do prazo, e zero `CONCLUIDO` sem reserva.
  > Exemplo: o webhook do Pix de um pedido da estável pode cair na nova. As duas conferem o HMAC e gravam com `UNIQUE(pagamento_id)`. O degrau de 20% devolve 100% à estável, sem desfazer migração. A estável segue dimensionada para 5.000 TPS.
  > Duas versões convivem com campos opcionais e chave `orderId`. A flag `saga_estados_v2` liga a saga nova e é um degrau do canary: o Argo Rollouts a opera, já em 1% e 5%, com os mesmos sinais. O rollback aos 20% lê o estado que a flag já gravou (`CONCLUIDO`, `CANCELADO_COM_ESTORNO`). Tabela: [anexo, canary](anexo-detalhes.md#canary).

### 2.2 Evolução de Schema Sem Downtime e Plano de Rollback

- **Passo a Passo da Migração:** Descreva em etapas sequenciais como evoluir a coluna `status_pagamento` para `estado_saga` no PostgreSQL garantindo que as instâncias da versão antiga (`v1`) e da versão nova (`v2`) operem juntas sem erros de SQL.
  > Decisão: expand/contract em três releases. A v1 do enunciado só lê a coluna antiga e fica no ar durante o expand. A v1.5 lê `status_pagamento` e grava as duas. A v2 lê `estado_saga` e também grava as duas. O contract (v2.1, `V12`) tira a antiga fora do pico.
  > Por quê: o Flyway renomeou a coluna no boot e os 80% que ainda liam `status_pagamento` quebraram. Nenhuma release apaga o que a anterior lê. A migração vira Job, com usuário próprio, `lock_timeout` de 3 s e `spring.flyway.enabled=false`. A aplicação não tem `ALTER`.
  > Exemplo: `V10` acrescenta `estado_saga` nula; `V11` copia em lotes de 5.000. `AGUARDANDO_PIX` vira `AGUARDANDO_PAGAMENTO`, `CONFIRMADO` vira `CONCLUIDO`, e `CANCELADO` vira sem cobrança, com estorno, ou revisão de estorno. O H2 desta aula, no backfill `V4`, ainda copia `CONFIRMADO` como está, porque o teste espera esse token. A escrita viva da v1.5 grava o nome canônico em `estado_saga` (`CONCLUIDO`) e o token antigo só em `status_pagamento` (`CONFIRMADO`). Antes de seguir: zero nulos e zero linha fora do mapa.
  > Script aplicado não se edita. SQL e pares: [anexo, migração](anexo-detalhes.md#migracao).
- **Garantia de Rollback Imediato:** Caso a versão `v2` apresente degradação grave de performance com 20% do tráfego na Black Friday, qual é o procedimento exato de rollback e por que o seu desenho de banco de dados garante que a versão `v1` voltará a assumir 100% da carga sem quebrar nem perder dados gravados pela `v2`?
  > Decisão: com 20% na v2 (cerca de 1.000 TPS), duas leituras ruins devolvem 100% à v1.5. Nenhuma migração é desfeita.
  > Por quê: as dez réplicas da v1.5 nunca saíram e já estão dimensionadas para 5.000 TPS. Elas ainda leem `status_pagamento`, então não encontram `column "status_pagamento" does not exist`. A v2 gravou as duas colunas.
  > Exemplo: o p99 da v2 sobe de 180 ms para 900 ms e o da v1.5 segue em 175 ms. `kubectl argo rollouts abort checkout` devolve o peso em segundos. A v2 termina o que está em andamento (até 30 s) e para de consumir o Kafka.
  > Pedido em `CONCLUIDO` continua legível: nome canônico na coluna nova e o token da v1.5 na antiga. `CANCELADO_COM_ESTORNO` convive com `CANCELADO`. Passos: [anexo, rollback](anexo-detalhes.md#rollback).

---

## 3. Guardrails de Segurança, Governança e Validação da Release

### 3.1 Gates de Segurança e Guardrails no Processo de Entrega

- **Guardrails do Commit ao Deploy:** Quais portões automatizados de qualidade e segurança devem proteger a sua esteira ao longo do fluxo de desenvolvimento (desde o Pull Request até a publicação e promoção da imagem)?
  > Decisão: o `RENAME` de `status_pagamento` para no pull request. `mvn -B verify` (JDK 21), gitleaks, CodeQL, hadolint e `flyway validate`. `DROP`, `RENAME` ou `ALTER ... TYPE` reprovam, salvo contract que prove que produção já não lê a coluna. `db/migration/` exige o time de dados. A `main` recusa push direto.
  > No merge, um build só: `cosign verify`, SBOM do Maven (jars) e do Trivy (imagem, pacotes e JRE), `trivy image --exit-code 1 --severity CRITICAL --ignore-unfixed`. CVE alta alerta e não bloqueia. A imagem sai assinada, com tag imutável. Tomcat 11.0.25 em cima do Spring Boot 4.1.1. Um scan que não está neste repositório não vira número.
  > Exemplo: o admission recusa imagem sem assinatura, por tag, como root ou sem SBOM. O canary é o último portão. Depois, o scan diário cruza o SBOM com CVE nova. Lista: [anexo, gates](anexo-detalhes.md#gates).
- **Governança e Controle em Produção:** Quais *guardrails* de processo e arquitetura impedem que alterações manuais não rastreadas cheguem à produção e como equilibrar segurança rigorosa com agilidade de resposta durante incidentes críticos sem perder a trilha de auditoria?
  > Decisão: produção só muda por GitOps. O Argo CD aplica o repositório e desfaz divergência. Não há `kubectl apply` pessoal nem build de produção: há promoção de um digest. A esteira usa OIDC que expira com a execução. A aplicação não tem `ALTER`.
  > Por quê: no incidente um jar de backup subiu sem origem e sem teste. Rollback de digest já aprovado é pré-aprovado (abort do canary ou revert). Hotfix usa os mesmos gates, uma revisão do plantão e degraus mais curtos. Não pula scan nem assinatura.
  > Exemplo: break-glass por 1 hora, com justificativa, alerta e auditoria. O que foi feito vira pull request; o Argo CD mostra a divergência até lá. [Anexo, governança](anexo-detalhes.md#governanca).

### 3.2 Estratégia de Testes da Imagem e da Migração

- **Validação Pré-Tráfego:** Passar nos testes unitários durante o build do código não prova que a imagem final do container sobe nem que a migração de banco é retrocompatível.
- **Plano de Testes na Pipeline:** Descreva como você testará automaticamente a imagem construída e validará a coexistência da migração de banco de dados na esteira antes de liberar o deploy para o tráfego real de clientes.
  > Decisão: antes do tráfego real, a esteira sobe as duas imagens no mesmo banco. Teste de jar, com uma versão só, foi o que passou no incidente e não pegou o `RENAME`.
  > O smoke usa o digest, usuário não root, disco só leitura e `/tmp` gravável. Espera readiness em até 60 s, vê que `docker run --entrypoint sh` falha e que o processo não é o uid 0. `scripts/smoke-test.sh` imprime `SMOKE OK`.
  > `MigracaoExpandContractTest` falha se algum script tiver `DROP` ou `RENAME`. Estoque esgotado termina `CANCELADO_SEM_COBRANCA` com zero cobrança. Reserva que expira termina `CANCELADO_COM_ESTORNO` com uma devolução. `PagamentoAprovado` com dinheiro capturado em `CANCELADO_SEM_COBRANCA` vai para `ESTORNANDO`.
  > Exemplo do limite: o H2 não segura `AccessExclusiveLock`. Verde no H2 não prova `ALTER` abaixo de 1 s em volume de produção. Esse portão está marcado como não executado no laboratório. Itens: [anexo, testes](anexo-detalhes.md#testes).

## Referências usadas

The Twelve-Factor App, fator III; Martin Fowler, "ParallelChange", "CanaryRelease" e "BlueGreenDeployment"; Distroless (GoogleContainerTools) e `cosign`; Flyway e Spring Boot, migrações; Docker, multi-stage builds; Trivy e CycloneDX; o `Dockerfile` e o `pom.xml` desta aula (Spring Boot 4.1.1, Tomcat 11.0.25, base `gcr.io/distroless/java21-debian13:nonroot`).
