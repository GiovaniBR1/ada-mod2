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

**Resumo das decisões (leitura em 30 segundos).** O incidente teve três causas, e cada uma tem uma correção própria: (1) o binário de produção foi **recompilado** e a tag **sobrescrita**, então construímos **uma vez**, identificamos a imagem pelo **digest** `sha256` e promovemos esse mesmo digest de staging para produção; (2) a migração fez uma mudança **destrutiva** (rename) no mesmo deploy que a trocou de versão, então usamos **Expand and Contract** em três releases, e nenhuma release apaga o que a anterior lê; (3) a versão nova entrou em 100% das réplicas de uma vez, então usamos **Canary** com reversão automática por sinais de negócio e técnicos. Escolha de rollout: **Canary**. A infraestrutura é código (Terraform, Kubernetes, Helm), sem ClickOps.

---

## 1. Empacotamento, Superfície de Ataque e Promoção Imutável

### 1.1 Anatomia da Imagem e Redução de Superfície de Ataque

- **Construção vs Execução:** Como você estruturará o empacotamento do serviço de Checkout para produção? Especifique o que deve ser usado na etapa de compilação, o que deve (e o que não deve) entrar na imagem final de execução, com qual permissão de usuário o processo rodará e qual o trade-off da sua escolha de imagem base para a segurança e para o diagnóstico em produção.
  > **Dockerfile multi-stage, em dois estágios.**
  >
  > 1. **Estágio de build** (`eclipse-temurin:21-jdk-jammy`): tem JDK 21 e Maven Wrapper. Copia primeiro só `pom.xml` e `.mvn/` e baixa as dependências (`RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline`), para que a camada de dependências fique em cache e só mude quando o `pom.xml` mudar. Depois copia `src/` e roda `./mvnw -B package` (os testes rodam no job de CI antes, ver 3.2; aqui `-DskipTests` só para não repetir). Extrai as camadas do Spring Boot (`java -Djarmode=tools -jar app.jar extract --layers`) para que dependências, recursos e classes virem camadas separadas na imagem final; um deploy que muda só código troca só a camada pequena.
  > 2. **Estágio de execução** (`gcr.io/distroless/java21-debian12:nonroot`): entra **só** a JRE 21, as camadas do jar e os certificados CA. **Não entra:** JDK, Maven, código-fonte, `~/.m2`, shell, `apt`, `curl`, segredos (nenhuma senha em `ENV` ou arquivo; ver 1.2). `COPY --from=builder --chown=65532:65532` e `USER nonroot` (UID 65532): o processo **não roda como root**; o sistema de arquivos do container é montado como somente leitura no Kubernetes (`readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities: drop: [ALL]`), com um `emptyDir` em `/tmp`.
  > 3. **Flags da JVM** no `ENTRYPOINT` em forma exec (`["java","-XX:MaxRAMPercentage=75","-jar",...]`) para a JVM respeitar o limite de memória do container e o processo receber o SIGTERM direto (desligamento gracioso: o Spring termina as requisições em curso com `server.shutdown=graceful`, o que importa a 5.000 TPS).
  >
  > **Trade-off da imagem base.** Distroless tira o shell e o gerenciador de pacotes: menos CVEs de sistema operacional (a comparação com Trivy entre `temurin:21-jdk` e a distroless mostra a queda) e um invasor que ganhe execução não tem `sh` nem `curl` para continuar. A imagem menor também baixa mais rápido em cada nó, o que acelera o auto-scaling no pico. O custo é o **diagnóstico**: não dá para `kubectl exec ... sh`. Mitigação, sem abrir mão da imagem mínima em produção: (a) observabilidade de fora para dentro (logs estruturados, métricas do Actuator/Micrometer, `/actuator/health`); (b) `kubectl debug` com container efêmero (`--target`) que traz as ferramentas só durante o incidente e deixa registro no audit log do cluster; (c) a variante `:debug` da distroless só em ambiente local ou staging, nunca promovida. Importante: distroless **não** resolve CVEs das bibliotecas Java do `pom.xml`; isso é coberto pelo SBOM e pelo scan (seção 3.1).
  >
  > **Por que imagem OCI e não um `.jar` solto.** O `.jar` depende da JRE, do sistema e das bibliotecas nativas de cada servidor; a imagem OCI leva tudo isso junto, tem um digest único e é aceita por qualquer runtime (containerd, CRI-O, Kubernetes, serviços gerenciados), mesmo sem Docker em produção. A unidade auditada e promovida passa a ser uma só.
  >
  > **Auditoria em todas as camadas:** o Trivy roda duas varreduras separadas, uma das bibliotecas do `pom.xml` (SCA) e outra dos pacotes do sistema da imagem base, porque a distroless reduz só a segunda.
  >
  > *Alternativa:* Paketo Buildpacks (`spring-boot:build-image`) é igualmente válida (não-root, camadas, SBOM). Prefiro o Dockerfile explícito porque cada item da imagem fica visível no PR; meta abaixo de 120 MB, medida com `docker image ls`.

### 1.2 Rastreabilidade, Identidade do Artefato e Parametrização

- **Da Homologação à Produção:** Como sua esteira garante que o binário executado em produção é exatamente o mesmo que passou nos testes de homologação? Explique como as configurações mudam entre ambientes (dev, staging, prod) sem recompilar o artefato e como você identifica a versão no registry para que um rollback nunca puxe um binário sobrescrito por engano.
  > **Construir uma vez, promover o mesmo digest.** Em ordem:
  >
  > 1. O merge na `main` dispara o **único** build da imagem. A imagem é publicada no registry com a tag `checkout:1.8.0+<git-sha-curto>` e o registry devolve o **digest** `checkout@sha256:...` (hash do manifesto OCI). A partir daqui, todo ambiente referencia o **digest**, não a tag.
  > 2. O registry tem **tags imutáveis** ligadas (ECR `IMMUTABLE`, Harbor/Artifactory com regra equivalente): tentar re-publicar `1.8.0` com conteúdo diferente falha. `latest` **não é usada** por nenhum manifesto de deploy (uma política do admission controller recusa pods com `:latest` ou sem digest).
  > 3. Staging recebe `checkout@sha256:abc...`; os testes de homologação rodam contra ele. A **promoção** para produção é um PR no repositório de deploy (GitOps, Argo CD) que copia **o mesmo digest**; o pipeline de produção não tem passo de build, só de deploy. Assim, "recompilar a `main` no deploy", que foi a causa do incidente, deixa de ser possível.
  > 4. A imagem é **assinada** (Cosign, assinatura ligada ao digest) e recebe a atestação do SBOM; o cluster de produção só aceita imagem assinada pela esteira (Kyverno ou Sigstore policy-controller).
  > 5. Rastreabilidade: labels OCI (`org.opencontainers.image.revision=<git sha>`, `version`, `source`) e o digest aparecem no `/actuator/info` e em cada log, então dá para ir de um erro em produção ao commit exato.
  >
  > **Configuração sem recompilar (12-factor).** A imagem é a mesma em dev, staging e prod; o que muda vem de fora: `SPRING_PROFILES_ACTIVE`, variáveis e `ConfigMap` (URLs, limites, percentuais de feature flag) e **segredos** do Secret Manager montados em tempo de execução (External Secrets), nunca dentro da imagem nem no repositório. Uma mudança de configuração também passa por PR no repositório de deploy e fica auditada.
  >
  > **Infraestrutura como Código e quem faz o quê (sem ClickOps).** Nenhum recurso é criado no console. **Terraform** provisiona a base (cluster Kubernetes, PostgreSQL gerenciado, registry com tags imutáveis, tópicos Kafka, permissões), com `plan` revisado no PR e `apply` só pela esteira; o state fica remoto e travado. **Kubernetes** executa e mantém o estado desejado (réplicas, probes, HPA, `securityContext`, Secrets montados). **Helm** empacota os manifestos do Checkout como um chart com um `values` por ambiente (`values-staging.yaml`, `values-prod.yaml`): réplicas, limites, URLs e o **digest** da imagem. A mesma imagem com `values` diferentes é o que muda entre dev, staging e prod. O Argo CD aplica o chart a partir do Git.
  >
  > **Rollback** = apontar o deploy para o **digest anterior**, que está registrado no histórico do GitOps e é imutável no registry (política de retenção mantém pelo menos as últimas 10 versões de produção). Não se reconstrói nada e não se depende de backup de binário.

---

## 2. Estratégia de Rollout e Migração de Banco sem Downtime

### 2.1 O Modelo de Rollout em Produção

- **A Escolha:** (x) Canary Release (Liberação Gradual por Percentual)  ( ) Blue-Green Deployment (Alternância de Ambientes)
- **Justificativa e Mitigação das Desvantagens do seu Modelo:**
  - *Se você escolheu Canary Release:* Como o tráfego evoluirá sob 5.000 TPS, quais sinais definirão o avanço ou a reversão (rollback) automática e como lidar com o tempo em que duas versões diferentes da API atendem clientes simultaneamente?
  - *Se você escolheu Blue-Green Deployment:* Como você gerenciará o custo de infraestrutura duplicada no pico da Black Friday, o aquecimento das novas instâncias (conexões/cache) antes da virada de chave e o risco de virar 100% do tráfego de uma só vez?
  > **Por que Canary.** O cenário tem um banco PostgreSQL **compartilhado** entre réplicas. Blue-Green troca a aplicação inteira de uma vez, mas o banco continua um só: as duas cores coexistem no mesmo schema de qualquer forma, então o Blue-Green não tira a necessidade de migração retrocompatível e ainda dobra a infraestrutura no pico e vira 100% de uma vez. O Canary expõe a versão nova a uma fração pequena e mede antes de crescer, que é exatamente o que faltou no incidente.
  >
  > **Evolução do tráfego (Argo Rollouts com malha de serviço ou ingress que divide por peso):** 1% por 10 min (a 5.000 TPS são cerca de 50 TPS, ou 30 mil requisições, amostra suficiente para ver erro de SQL ou de saga) → 5% por 10 min → 20% por 15 min → 50% por 15 min → 100%. Cada degrau só avança se a **análise automática** passar; nenhuma pessoa precisa clicar para avançar, mas uma pessoa pode **pausar** ou **abortar** a qualquer momento.
  >
  > **Sinais de avanço ou reversão automática (comparando canary com a versão estável no mesmo intervalo):**
  > - Técnicos: taxa de erro 5xx do canary não pode passar de 0,5% nem ser mais que 1,5 vez a da estável; latência p99 não pode subir mais que 20%; nenhum erro de SQL (`undefined_column`, `undefined_table`) nos logs; pods sem reinício e com `readiness` ok; pool de conexões abaixo de 80%.
  > - De negócio (o que realmente quebra o checkout): taxa de pedidos que chegam a `CONFIRMADO` por pedidos criados, crescimento de pedidos parados em estado intermediário (`AGUARDANDO_PAGAMENTO`, `RESERVANDO_ESTOQUE`) além do prazo, e **zero** pedido confirmado sem reserva de estoque. Duas leituras ruins seguidas abortam: o tráfego volta 100% para a estável em segundos (só muda o peso da rota; as réplicas estáveis nunca saíram do ar).
  >
  > **Duas versões ao mesmo tempo.** É o ponto mais delicado do Canary, e ele é tratado em três camadas:
  > 1. **Banco:** as duas versões funcionam com o mesmo schema por construção (Expand and Contract, item 2.2).
  > 2. **API:** a v2 só **adiciona** campos opcionais; nenhum campo é removido ou muda de sentido na mesma release. Clientes que caírem ora na v1 ora na v2 recebem respostas compatíveis. Funcionalidade nova entra desligada por feature flag e é ligada depois que a v2 está em 100%.
  > 3. **Mensagens e saga:** os eventos da saga são publicados com **chave de partição `orderId`**. O Kafka só garante ordem dentro de uma partição, e a chave igual manda todos os eventos de um mesmo pedido para a mesma partição; assim, mesmo com consumidores v1 e v2 no mesmo grupo durante o canary, os eventos de um pedido são processados em ordem por um consumidor de cada vez, e a troca de dono da partição no rebalanceamento não embaralha a sequência daquele pedido. O formato dos eventos só ganha campos opcionais (o consumidor v1 ignora campo desconhecido) e os consumidores continuam **idempotentes** (tabela de mensagens processadas com `UNIQUE(message_id)`), porque o rebalanceamento pode entregar a mesma mensagem duas vezes. O `UPDATE ... WHERE estado = :esperado` (transição condicional) continua barrando mensagem velha.
  >
  > **O webhook do PSP também passa pelo canary.** O webhook de confirmação do Pix pode cair em v1 ou v2; os dois verificam a assinatura HMAC, gravam o evento pela chave `pagamento_id` com `UNIQUE` (webhook repetido não muda nada) e aplicam a mesma transição condicional. Durante o rollout, o limite de exposição em `PENDENTE` continua o mesmo nas duas versões (soma do valor de pedidos `PENDENTE` por cliente e no total com teto em configuração; acima do teto, novos Pix esperam), para que uma regressão do canary não aumente o dinheiro em risco.

### 2.2 Evolução de Schema Sem Downtime e Plano de Rollback

- **Passo a Passo da Migração:** Descreva em etapas sequenciais como evoluir a coluna `status_pagamento` para `estado_saga` no PostgreSQL garantindo que as instâncias da versão antiga (`v1`) e da versão nova (`v2`) operem juntas sem erros de SQL.
  > **Regra geral:** nenhuma release apaga ou renomeia algo que a release anterior ainda lê. O rename vira **Expand, Migrate, Contract**, espalhado em três releases. As migrações rodam como **Job separado antes do deploy** (não no boot de cada réplica, que foi o que disparou o incidente), com `spring.flyway.enabled=false` nas réplicas e um usuário de banco de migração diferente do usuário da aplicação (o da aplicação não tem permissão de `ALTER`/`DROP`).
  >
  > **Release A (expand, v1.5): só adiciona.**
  > 1. `V10__expand_estado_saga.sql`: `ALTER TABLE pedido ADD COLUMN estado_saga VARCHAR(40) NULL;` (sem `DEFAULT` com valor nem `NOT NULL`, então no PostgreSQL é só metadado, instantâneo, sem reescrever a tabela). Com `SET lock_timeout = '3s'` para não ficar preso atrás de transação longa a 5.000 TPS; se não conseguir o lock, o Job falha e repete, sem travar o checkout.
  > 2. A aplicação v1.5 faz **escrita dupla**: toda transição grava `status_pagamento` e `estado_saga` na mesma transação; a leitura continua por `status_pagamento`.
  > 3. **Mapeamento de valores, sem perder informação:** a coluna antiga tinha um único `CANCELADO`. Na coluna nova, o cancelamento é separado em `CANCELADO_SEM_COBRANCA` (nada foi cobrado: sem estoque antes do Pix, Pix não pago no prazo) e `CANCELADO_COM_ESTORNO` (houve cobrança e o estorno foi confirmado pelo PSP), mais `ESTORNO_EM_REVISAO` (estorno pedido sem confirmação). A escrita dupla decide pelo motivo da transição; quando o valor antigo é ambíguo (só `CANCELADO`), o backfill usa a tabela de pagamentos: existe pagamento capturado e estorno confirmado → `CANCELADO_COM_ESTORNO`; sem pagamento capturado → `CANCELADO_SEM_COBRANCA`; capturado sem estorno confirmado → `ESTORNO_EM_REVISAO` (o job de reconciliação da seção 3.2 resolve). Na coluna antiga a v1.5 continua gravando `CANCELADO`, para a v1 ler normalmente.
  >
  > **Migrate (ainda na v1.5, depois que 100% está nela):**
  > 4. Backfill em lotes pequenos de 5.000 linhas, por faixa de `id`, em transações curtas, com pausa entre lotes e `WHERE estado_saga IS NULL` (rodar duas vezes não estraga nada; pode ser interrompido e retomado). Monitora o atraso de replicação e para se passar de um limite.
  > 5. Verificação: `SELECT count(*) FROM pedido WHERE estado_saga IS NULL` tem de ser 0, e uma consulta de conferência compara as duas colunas pelo mapeamento (nenhuma divergência). Só então: `ALTER TABLE ... ADD CONSTRAINT estado_saga_nn CHECK (estado_saga IS NOT NULL) NOT VALID;` seguido de `VALIDATE CONSTRAINT` (valida sem bloquear escrita).
  >
  > **Release B (v2, a otimização da saga): passa a ler a coluna nova.**
  > 6. A v2 **lê** `estado_saga` e **continua gravando as duas colunas**. É esta release que vai para o canary. Durante o canary, v1.5 e v2 gravam e leem ambas as colunas: nenhuma das duas faz SQL com coluna que não existe.
  >
  > **Release C (contract, v2.1), em release futura, depois de a v2 ficar estável (por exemplo, uma semana, fora do pico):**
  > 7. A v2.1 para de gravar `status_pagamento`. Depois de 100% na v2.1 e de passada a janela de rollback, `V12__contract_drop_status_pagamento.sql` remove a coluna. A partir daqui, voltar para a v1/v1.5 não é mais possível, e isso é decidido de forma consciente, registrado no PR, e nunca no meio de uma Black Friday.
  >
  > **Imutabilidade das migrações:** script aplicado nunca é editado (o `flyway validate` falha por checksum se alguém tentar); correção vira uma nova versão.
- **Garantia de Rollback Imediato:** Caso a versão `v2` apresente degradação grave de performance com 20% do tráfego na Black Friday, qual é o procedimento exato de rollback e por que o seu desenho de banco de dados garante que a versão `v1` voltará a assumir 100% da carga sem quebrar nem perder dados gravados pela `v2`?
  > **Procedimento, em ordem:**
  > 1. A análise do canary detecta a degradação (p99 acima do limite em duas leituras seguidas) e **aborta sozinha**; ou o plantonista roda `kubectl argo rollouts abort checkout`. O peso da rota volta a 100% para a versão estável (`v1.5`, que é a "v1" do enunciado já com escrita dupla) em segundos. As réplicas estáveis estavam no ar e aquecidas, então não há cold start; o HPA delas já estava dimensionado para 100% (o canary roda **além** da capacidade estável, não no lugar dela).
  > 2. As réplicas v2 recebem SIGTERM, terminam as requisições em curso (desligamento gracioso) e param de consumir do Kafka; as partições voltam para os consumidores v1.5 (rebalanceamento) e eles continuam do último offset confirmado. Mensagem reprocessada é descartada pela idempotência.
  > 3. **Nenhuma migração é desfeita.** Não existe "down migration" no caminho de rollback: o schema do momento (as duas colunas) já é o schema que a v1.5 usa.
  > 4. O commit de rollback fica no histórico do GitOps e abre-se um post-mortem.
  >
  > **Por que não quebra nem perde dados:** (a) a coluna `status_pagamento` nunca foi removida nem renomeada enquanto a v2 existe, então a v1.5 não encontra `column does not exist`; (b) a v2 gravou **as duas colunas** em cada transição, então todo pedido criado ou alterado pela v2 tem `status_pagamento` correto para a v1.5 ler; (c) os novos valores de cancelamento da v2 também têm equivalente na coluna antiga (`CANCELADO`), e o detalhe continua guardado em `estado_saga`, que a v1.5 também mantém; (d) pedidos que estavam no meio da saga continuam pela máquina de estados com transições condicionais, e mensagens da v2 têm só campos opcionais a mais. Se, ainda assim, algum pedido ficar parado num estado intermediário por causa da troca, o job de reconciliação (3.2) o encontra pelo prazo vencido.

---

## 3. Guardrails de Segurança, Governança e Validação da Release

### 3.1 Gates de Segurança e Guardrails no Processo de Entrega

- **Guardrails do Commit ao Deploy:** Quais portões automatizados de qualidade e segurança devem proteger a sua esteira ao longo do fluxo de desenvolvimento (desde o Pull Request até a publicação e promoção da imagem)?
  > Em ordem, cada portão bloqueia o próximo:
  > 1. **No PR (GitHub Actions):** build e testes unitários e de integração (`./mvnw -B verify` com JDK 21, cache Maven); varredura de segredos (gitleaks); SCA das dependências do `pom.xml`; SAST (CodeQL); lint do Dockerfile (hadolint); **validação das migrações**: `flyway validate` (checksum) e uma regra que **reprova** migração com `DROP`, `RENAME` ou `ALTER ... TYPE` fora de um PR marcado como fase *contract* (e esse PR exige que a release anterior já não leia a coluna). Exige revisão de outra pessoa (CODEOWNERS para `db/migration/`) e branch protegida.
  > 2. **No merge (build único):** gera SBOM CycloneDX (`cyclonedx:makeAggregateBom` e SBOM da imagem com Trivy); `trivy image --exit-code 1 --severity CRITICAL --ignore-unfixed` (falha em CVE crítica com correção disponível; High gera alerta e comentário no PR); verifica tamanho da imagem e que o usuário não é root; publica no registry com tag imutável; assina com Cosign e anexa SBOM como atestação.
  > 3. **Antes do tráfego (staging):** os testes da seção 3.2 contra o **mesmo digest**.
  > 4. **Promoção:** só por PR no repositório GitOps; o admission controller de produção recusa imagem sem assinatura válida, sem digest, com `:latest`, como root, ou sem SBOM.
  > 5. **Durante o rollout:** a análise automática do canary (2.1) é o último portão.
  > 6. **Depois:** varredura diária das imagens em produção contra CVEs novas, cruzando com o SBOM.
- **Governança e Controle em Produção:** Quais *guardrails* de processo e arquitetura impedem que alterações manuais não rastreadas cheguem à produção e como equilibrar segurança rigorosa com agilidade de resposta durante incidentes críticos sem perder a trilha de auditoria?
  > **Impedir alteração manual:** produção só muda por GitOps (Argo CD sincroniza o estado do repositório e reverte *drift* manual); ninguém tem `kubectl apply` nem acesso de escrita ao registry com credencial pessoal (a esteira usa identidade OIDC de curta duração); o usuário de banco da aplicação não tem DDL; DDL só pelo Job de migração versionado; o build de produção não existe (só promoção de digest).
  >
  > **Agilidade em incidente sem perder auditoria:**
  > - O **rollback é a ação mais rápida e já é pré-aprovado**: abortar o canary ou reverter o commit no GitOps não exige aprovação extra, porque volta para um digest que já passou por todos os portões.
  > - **Hotfix** passa pela mesma esteira em modo rápido (os mesmos portões de segurança, revisão por uma pessoa do plantão, canary com degraus mais curtos); nunca pula o scan nem a assinatura.
  > - **Break-glass**: um papel de emergência com acesso elevado, liberado por tempo limitado (por exemplo, 1 hora), com justificativa obrigatória, alerta para o time de segurança e todas as ações gravadas no audit log; toda mudança feita assim precisa virar PR depois (o Argo CD aponta o drift até isso acontecer).
  > - Diagnóstico com `kubectl debug` (container efêmero) também fica no audit log.

### 3.2 Estratégia de Testes da Imagem e da Migração

- **Validação Pré-Tráfego:** Passar nos testes unitários durante o build do código não prova que a imagem final do container sobe nem que a migração de banco é retrocompatível.
- **Plano de Testes na Pipeline:** Descreva como você testará automaticamente a imagem construída e validará a coexistência da migração de banco de dados na esteira antes de liberar o deploy para o tráfego real de clientes.
  > **1. Smoke test da imagem real (o digest publicado, não o jar):** sobe o container com o mesmo `securityContext` de produção (não-root, filesystem somente leitura) e um PostgreSQL e um Kafka efêmeros (Testcontainers ou `docker compose` no job); espera `/actuator/health/readiness` responder 200 dentro de 60 s; faz um pedido de ponta a ponta; confere que `docker run --entrypoint sh` **falha** (não há shell) e que o processo não é UID 0.
  >
  > **2. Teste de migração com cópia do schema de produção:** restaura um dump de schema de produção com volume de dados sintético; aplica as migrações novas e mede duração e locks (falha se algum `ALTER` pedir `ACCESS EXCLUSIVE` por mais de 1 s ou reescrever a tabela).
  >
  > **3. Teste de coexistência N e N-1 (o teste que teria pegado o incidente):** com o schema já migrado, sobe **a imagem atual de produção (digest anterior) e a imagem nova ao mesmo tempo**, apontando para o mesmo banco e o mesmo tópico. Roda tráfego misturado (pedidos criados por uma versão e avançados pela outra). Passa só se:
  > - nenhuma das duas registra erro de SQL;
  > - pedidos criados pela v2 são lidos corretamente pela v1 (e o contrário), com `status_pagamento` e `estado_saga` coerentes pelo mapeamento;
  > - depois de derrubar a v2 no meio do tráfego (simulação do rollback), a v1 conclui todos os pedidos em andamento.
  >
  > **4. Testes da saga com verificação negativa** (no mesmo ambiente, com o PSP e o Estoque simulados):
  > - *Estoque esgotado antes do Pix:* espera `CANCELADO_SEM_COBRANCA`, **nenhuma** chamada de cobrança e **nenhuma** de estorno; o teste falha se aparecer qualquer pedido `CONFIRMADO` ou um evento `PedidoConfirmado` sem reserva.
  > - *Reserva expirou durante Pix lento:* espera `CANCELADO_COM_ESTORNO` e **exatamente uma** chamada de compensação (estorno) ao PSP, mesmo com o evento de pagamento entregue **duas vezes** e com o webhook repetido; falha se houver zero ou duas.
  > - *Timeout do Pagamento:* o simulador não responde; o teste espera que a saga **consulte o status no PSP antes de decidir** e verifica que **não houve** estorno nem cancelamento enquanto o status era desconhecido; com o status "pago" a saga segue, com "não pago" cancela sem cobrança.
  > - *Timeout do Estoque:* o simulador de Estoque não responde à reserva; espera consulta do status da reserva pelo `orderId` antes de decidir (reserva existe → segue; não existe → cancela sem cobrança, pois a reserva vem antes do Pix); falha se o pedido for confirmado sem reserva confirmada ou se uma liberação for enviada sem reserva conhecida.
  > - *Ordem:* eventos de um mesmo `orderId` produzidos fora de ordem por v1 e v2 são rejeitados pela transição condicional (o estado final é o correto).
  >
  > **5. Job de reconciliação (também testado):** roda a cada 5 minutos e procura pedidos em estado de revisão ou falha (`ESTORNO_EM_REVISAO`, `PAGAMENTO_EM_VERIFICACAO`, estados intermediários com prazo vencido). Para cada um, **consulta o status real no parceiro** (PSP para pagamento e estorno; Estoque para a reserva) e aplica a transição correspondente: estorno confirmado no PSP → `CANCELADO_COM_ESTORNO`; pagamento confirmado sem estoque → pede estorno (idempotente por `pagamento_id`); estorno recusado ou sem resposta → tenta de novo com backoff até 24 h; depois disso, ou se o PSP recusar de vez, move para `ESTORNO_FALHOU`, abre caso no time financeiro com alerta e continua consultando uma vez por dia. Teste: um pedido parado em `ESTORNO_EM_REVISAO` com o PSP simulado dizendo "estornado" tem de sair sozinho para `CANCELADO_COM_ESTORNO` na próxima execução, sem nova chamada de estorno.
  >
  > **6. Portão final:** só se 1 a 5 passarem, o PR de promoção para produção é aberto com o digest testado; o canary (2.1) faz a validação com tráfego real e reversão automática.

---

## Trade-offs e o que foi descartado

| Decisão | Ganho | Custo | Descartado e por quê |
| :--- | :--- | :--- | :--- |
| Distroless não-root | menos CVEs, sem shell para invasor, imagem menor | diagnóstico mais difícil | imagem JDK completa (superfície grande) |
| Digest imutável + GitOps | rollback em segundos, auditoria | registry com retenção, disciplina de PR | `:latest` (causa do incidente) |
| Canary | blast radius pequeno, rollback por peso de rota | duas versões ao mesmo tempo exigem compatibilidade | Blue-Green (dobra a infra no pico e não resolve o banco compartilhado) |
| Expand and Contract em 3 releases | v1 e v2 juntas, rollback sem desfazer schema | mais releases, escrita dupla temporária | rename em um passo (causa do incidente) |
| Migração como Job antes do deploy | uma execução controlada, com lock_timeout | um passo a mais na esteira | Flyway no boot de cada réplica |
