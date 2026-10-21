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

---

## Glossário usado nas respostas

- **Imagem**: pacote fechado com o programa e o que ele precisa para rodar. Cada servidor que roda essa imagem é uma **réplica**.
- **Tag**: apelido de uma imagem no registry (o depósito de imagens), como `checkout:latest`. O apelido pode passar a apontar para outra imagem.
- **Digest**: impressão digital da imagem (`sha256:...`). Calculada do conteúdo: não muda e não aponta para outra coisa.
- **Esteira** (pipeline): o que compila, testa e publica depois de cada commit.
- **Migração**: script SQL versionado que o Flyway aplica no banco. O Flyway guarda quais scripts já rodaram.
- **Saga**: a sequência que reserva estoque, cobra o Pix e confirma o pedido. Os estados citados abaixo são os do [apêndice da Aula 4](../Aula%204/apendice-maquina-de-estados.md).

O incidente juntou três erros: a esteira recompilou em vez de promover o que foi testado, a tag `latest` foi sobrescrita, e a migração renomeou a coluna `status_pagamento` que a versão anterior ainda lia. Com 5.000 TPS e várias réplicas no mesmo PostgreSQL, 80% da frota passou a receber `column "status_pagamento" does not exist`, e o rollback puxou a tag já apontando para a imagem nova. As seções desfazem os três, na ordem da release.

---

## 1. Empacotamento, Superfície de Ataque e Promoção Imutável

### 1.1 Anatomia da Imagem e Redução de Superfície de Ataque

- **Construção vs Execução:** Como você estruturará o empacotamento do serviço de Checkout para produção? Especifique o que deve ser usado na etapa de compilação, o que deve (e o que não deve) entrar na imagem final de execução, com qual permissão de usuário o processo rodará e qual o trade-off da sua escolha de imagem base para a segurança e para o diagnóstico em produção.
  > **Decisão:** a imagem de produção é construída uma vez, em dois estágios, e só o segundo vai para o cluster. A esteira roda `docker build -t checkout:1.6.0 .`.
  >
  > **Estágio 1, a oficina.** Parte de `eclipse-temurin:21-jdk-jammy`, que traz o JDK 21 (o kit completo para compilar Java). Ali dentro o Maven baixa as dependências e roda `mvn -B package`. No fim, o comando `java -Djarmode=tools -jar app.jar extract --layers` separa o jar em quatro camadas: dependências de terceiros, o carregador do Spring Boot, dependências em desenvolvimento e o nosso código. Separar ajuda porque o Docker reaproveita uma camada que não mudou. Na próxima release, só a camada do nosso código (poucos KB) é reconstruída e enviada.
  >
  > **Estágio 2, a vitrine.** Parte de `gcr.io/distroless/java21-debian13:nonroot`. "Distroless" quer dizer uma imagem que só tem o Java e as bibliotecas mínimas do sistema. Ela não tem shell (o terminal `sh`), não tem gerenciador de pacotes (`apt`) e não tem `curl`. A base é fixada pelo digest dela, não só pelo nome, para que o mesmo Dockerfile gere sempre a mesma coisa. O estágio 2 copia apenas as quatro camadas do estágio 1. Não entram na imagem final: o JDK, o Maven, o código-fonte, o cache `~/.m2`, os testes, os dados de exemplo usados nos testes, arquivos `.env` e qualquer segredo.
  >
  > **Com qual usuário.** A imagem declara `USER nonroot`, que é o usuário 65532, sem privilégios. No Kubernetes, o `securityContext` reforça isso com `runAsNonRoot: true`, `readOnlyRootFilesystem: true` (o disco do container é só leitura) e `allowPrivilegeEscalation: false`. O filesystem só leitura não tem onde a JVM gravar o diretório temporário: no distroless ela sobe e escreve artefato de diagnóstico em `/tmp`. O pod monta um volume vazio só nesse caminho:
  >
  > ```yaml
  > volumeMounts:
  >   - name: tmp
  >     mountPath: /tmp
  > volumes:
  >   - name: tmp
  >     emptyDir: {}
  > ```
  >
  > O processo continua sem ser administrador, sem shell e sem gravar no resto do disco. Se alguém achar uma falha no código, cai nesse processo. `emptyDir` some quando o pod morre; não é volume de dados do pedido.
  >
  > **Por que isso pesa no cenário.** Uma imagem de execução não é um jar dentro de uma caixa. Ela é um sistema operacional inteiro: bibliotecas do Linux, a JVM e os jars. Cada pacote a mais é uma porta a mais para uma CVE, que é uma falha de segurança pública com número de registro. Por isso a esteira audita duas camadas: as dependências Java (análise de composição, ou SCA) e os pacotes do sistema da imagem. Na prática, no projeto desta aula, a imagem distroless tem 220 MB e zero CVE crítica. Com a base `jdk` completa, o mesmo serviço passaria de 450 MB e levaria compilador e ferramentas que ninguém usa em produção.
  >
  > **O trade-off.** A segurança melhora e o diagnóstico fica mais difícil. Na Black Friday, o plantonista não consegue entrar no container e rodar `sh` para olhar um arquivo. A resposta é preparar o diagnóstico antes do incidente: logs estruturados com o `pedido_id` e o digest em cada linha, métricas e traces pelo Actuator e pelo OpenTelemetry (a biblioteca que mede cada requisição de ponta a ponta), e, quando precisar mesmo de um terminal, `kubectl debug --image=busybox --target=checkout` cria um container temporário ao lado do processo, sem mexer na imagem, e esse acesso fica gravado no registro de auditoria. A alternativa Paketo Buildpacks (`mvn spring-boot:build-image`) gera uma imagem parecida sem Dockerfile e foi considerada. O Dockerfile ganhou porque a aula pede para mostrar cada estágio, mas o Buildpacks com `BP_JVM_JLINK_ENABLED=true` é o caminho para chegar perto de 120 MB, porque monta uma JRE só com os módulos que o serviço usa.

### 1.2 Rastreabilidade, Identidade do Artefato e Parametrização

- **Da Homologação à Produção:** Como sua esteira garante que o binário executado em produção é exatamente o mesmo que passou nos testes de homologação? Explique como as configurações mudam entre ambientes (dev, staging, prod) sem recompilar o artefato e como você identifica a versão no registry para que um rollback nunca puxe um binário sobrescrito por engano.
  > **Um build, muitas promoções.** No incidente, a esteira compilou de novo na hora de ir para produção, e o que rodou lá nunca tinha sido testado. Agora a imagem é construída uma vez, no merge, e publicada assim:
  >
  > ```bash
  > docker build -t registry/checkout:1.6.0-a1b2c3d .
  > docker push registry/checkout:1.6.0-a1b2c3d
  > # o registry devolve: digest sha256:9b2e7c...f41a (exemplo)
  > ```
  >
  > A tag junta a versão (`1.6.0`) e o commit (`a1b2c3d`), e o registry está configurado com tags imutáveis: um segundo `push` com a mesma tag é recusado. A tag serve para pessoas lerem. O que vai para os ambientes é o digest, porque ele é a impressão digital do conteúdo. Staging roda `registry/checkout@sha256:9b2e7c...f41a`. Quando os testes passam, a promoção para produção é um PR de uma linha no repositório de deploy, trocando o digest antigo pelo novo. Ninguém roda `docker build` de novo. O binário que vai para produção é, byte a byte, o que passou em staging.
  >
  > **A mesma imagem, ambientes diferentes.** Nada que muda entre ambientes fica dentro da imagem. Seguindo o fator III do 12-Factor App (configuração mora no ambiente), o `application.yml` só tem valores que leem variáveis: `SPRING_DATASOURCE_URL`, `PIX_PSP_URL`, `SPRING_PROFILES_ACTIVE`. Em dev, elas apontam para um banco local. Em staging e produção, vêm de um ConfigMap (configuração comum do Kubernetes) e de um Secret (senha do banco, chave HMAC do PSP), entregues pelo cofre de segredos. Essas definições ficam em arquivos versionados no Git, num chart Helm com um arquivo de valores por ambiente, e a rede e o banco são descritos em Terraform. Isso é Infraestrutura como Código: a infraestrutura muda por PR, com revisão, e não por clique no console.
  >
  > **Como a versão aparece.** A imagem carrega etiquetas OCI, que são metadados padrão gravados nela: `org.opencontainers.image.revision=a1b2c3d` e `org.opencontainers.image.version=1.6.0`. O `/actuator/info` devolve essas mesmas informações, e cada linha de log leva o digest. Assim, um erro em produção leva direto ao commit exato.
  >
  > **O rollback que não pode puxar a coisa errada.** O repositório de deploy guarda o histórico de digests. Voltar para a versão anterior é reverter o último commit, que traz de volta `sha256:5d0a...` (a `v1.5`), e esse digest não pode ter sido sobrescrito, porque digest não se sobrescreve. A política de retenção do registry guarda pelo menos as últimas 10 versões que estiveram em produção. A tag `latest` não é usada em nenhum manifesto, e o controle de admissão do cluster (o porteiro que valida cada objeto antes de ele entrar) recusa qualquer imagem referenciada por tag em vez de digest.

---

## 2. Estratégia de Rollout e Migração de Banco sem Downtime

### 2.1 O Modelo de Rollout em Produção

- **A Escolha:** (X) Canary Release (Liberação Gradual por Percentual)  ( ) Blue-Green Deployment (Alternância de Ambientes)
- **Justificativa e Mitigação das Desvantagens do seu Modelo:**
  - *Se você escolheu Canary Release:* Como o tráfego evoluirá sob 5.000 TPS, quais sinais definirão o avanço ou a reversão (rollback) automática e como lidar com o tempo em que duas versões diferentes da API atendem clientes simultaneamente?
  - *Se você escolheu Blue-Green Deployment:* Como você gerenciará o custo de infraestrutura duplicada no pico da Black Friday, o aquecimento das novas instâncias (conexões/cache) antes da virada de chave e o risco de virar 100% do tráfego de uma só vez?
  > **Por que Canary.** No canary, uma fração pequena dos clientes usa a versão nova enquanto o resto continua na estável, e a fração só cresce se as medições estiverem boas. No Blue-Green, existe uma segunda frota inteira (a "verde") e todo o tráfego vira de uma vez. O nosso banco é um só. Mesmo no Blue-Green, as duas frotas usariam o mesmo schema durante a virada, então a migração precisaria ser compatível de qualquer jeito. O Blue-Green ainda dobraria a frota no pico da Black Friday e jogaria 100% dos clientes na versão nova num único clique. O canary mostra o problema para 1% dos clientes, e não para todos.
  >
  > **Como o tráfego anda.** O PR de promoção troca o digest da `v2` (`sha256:9b2e...`, exemplo). O Argo Rollouts, que controla os degraus do canary, cria réplicas `v2` **além** das dez `v1.5`, que continuam no ar e aquecidas. Os degraus são:
  >
  > | Degrau | Tráfego na v2 | A 5.000 TPS | Duração |
  > | :--- | :--- | :--- | :--- |
  > | 1 | 1% | ~50 TPS, ~30 mil requisições | 10 min |
  > | 2 | 5% | ~250 TPS | 10 min |
  > | 3 | 20% | ~1.000 TPS | 15 min |
  > | 4 | 50% | ~2.500 TPS | 15 min |
  > | 5 | 100% | 5.000 TPS | fim |
  >
  > Antes de receber o primeiro cliente, cada réplica `v2` só fica pronta quando o `/actuator/health/readiness` responde 200, o que já inclui o pool de conexões aberto e o cache aquecido. Em cada degrau, uma análise automática compara a `v2` com a `v1.5` no mesmo intervalo de tempo e decide sozinha se avança.
  >
  > **Os sinais que decidem.** Os técnicos: a taxa de erro HTTP 5xx da `v2` não passa de 0,5% nem de 1,5 vez a da `v1.5`; a latência p99 (o tempo que 99% das requisições não ultrapassam) não sobe mais de 20%; nenhum log com erro de SQL de coluna ou tabela inexistente; nenhuma réplica reiniciando; o pool de conexões abaixo de 80%. Os de negócio, que mostram se o checkout funciona de verdade: a proporção de pedidos criados que chegam a `CONCLUIDO`, o número de pedidos parados em `AGUARDANDO_PAGAMENTO` ou `PENDENTE` além do prazo, e zero pedido `CONCLUIDO` sem reserva de estoque. Duas leituras ruins seguidas fazem a análise abortar. O que acontece depois está em 2.2.
  >
  > **Duas versões atendendo ao mesmo tempo.** Durante 50 minutos, um cliente pode criar o pedido na `v1.5` e o webhook do Pix desse pedido cair na `v2`. Isso funciona por quatro motivos:
  >
  > 1. **Banco:** as duas versões funcionam com o mesmo schema, por construção (seção 2.2).
  > 2. **API:** a `v2` só acrescenta campos opcionais nas respostas. Nada some e nada muda de sentido nesta release. O comportamento novo da saga entra desligado por uma flag de configuração e só é ligado com a `v2` em 100%.
  > 3. **Mensagens da saga:** os eventos vão para o Kafka com a chave `orderId`. O Kafka só garante ordem dentro de uma partição, e a mesma chave sempre cai na mesma partição. Assim, os eventos de um pedido são lidos em ordem, por um consumidor de cada vez, mesmo que esse consumidor troque de `v1.5` para `v2` no meio do canary. Quando o Kafka redistribui as partições, ele pode entregar uma mensagem duas vezes. Por isso, todo consumidor é idempotente, isto é, processar a mesma mensagem duas vezes tem o mesmo efeito que processar uma: ele grava o `message_id` numa tabela com `UNIQUE` e ignora o repetido. O reenvio do job usa esse mesmo `message_id` (apêndice), não um id novo. Além disso, toda mudança de estado é condicional (`UPDATE pedido SET ... WHERE id = ? AND estado_saga = 'AGUARDANDO_PAGAMENTO'`), então uma mensagem velha que chega atrasada não faz o pedido voltar no tempo.
  > 4. **Webhook do PSP:** o PSP é o parceiro que processa o Pix. O webhook de confirmação pode cair em qualquer versão. As duas verificam a assinatura HMAC (prova de que veio mesmo do PSP), gravam o evento com `UNIQUE(pagamento_id)` e aplicam a mesma transição condicional. As duas versões também respeitam o mesmo teto de Pix em `AGUARDANDO_PAGAMENTO` (a soma dos Pix gerados e ainda não confirmados, por cliente e no total). O número do teto é o medido na Aula 6, não um valor chutado. Se a `v2` tiver um defeito que deixa Pix sem confirmação, o teto impede que o valor em risco cresça sem limite: acima dele, novos Pix esperam.

### 2.2 Evolução de Schema Sem Downtime e Plano de Rollback

- **Passo a Passo da Migração:** Descreva em etapas sequenciais como evoluir a coluna `status_pagamento` para `estado_saga` no PostgreSQL garantindo que as instâncias da versão antiga (`v1`) e da versão nova (`v2`) operem juntas sem erros de SQL.
  > **A regra.** Nenhuma release apaga ou renomeia algo que a release anterior ainda lê. O rename de uma linha, que derrubou o checkout, vira três releases, num padrão chamado Expand and Contract (expandir e depois contrair, também conhecido como Parallel Change): primeiro o schema ganha o novo, depois os dados são copiados, e só no fim o velho sai. E a migração deixa de rodar no boot de cada réplica. Ela roda como um Job do Kubernetes antes do rollout, com um usuário de banco próprio. As réplicas têm `spring.flyway.enabled=false`, e o usuário de banco da aplicação não tem permissão de `ALTER` nem de `DROP`.
  >
  > **Por que a coluna nova muda os valores.** Os nomes são os do apêndice. A coluna antiga tinha um `CANCELADO` só, e ele escondia o pedido que nunca foi cobrado e o pedido que foi cobrado e estornado. Na coluna nova isso vira `CANCELADO_SEM_COBRANCA`, `CANCELADO_COM_ESTORNO` ou `ESTORNO_EM_REVISAO`. Os outros valores não são copiados crus: `AGUARDANDO_PIX` vira `AGUARDANDO_PAGAMENTO`, `CONFIRMADO` vira `CONCLUIDO`, `RESERVANDO_ESTOQUE` vira `PENDENTE`. `PAGO` e `CONCLUIDO` já são o nome canônico e permanecem. Um valor sem mapa faz a migração falhar, em vez de gravar na coluna nova um nome que a v2 não usa. A coluna antiga guarda o token que a v1 ainda lê.
  >
  > A tabela abaixo mostra cada fase, o que existe no banco e o que cada versão lê e grava:
  >
  > | Fase | Colunas na tabela `pedido` | A réplica v1 (atual) | A réplica v1.5 / v2 |
  > | :--- | :--- | :--- | :--- |
  > | Antes | `status_pagamento` | lê e grava `status_pagamento` | não existe |
  > | A. Expand (`V10`) | as duas; `estado_saga` aceita vazio | lê e grava `status_pagamento`; não sabe que a nova existe | v1.5 lê a antiga e grava as duas |
  > | B. Migrate (`V11`) | as duas, todas preenchidas | sai de cena: a frota inteira já é v1.5 | v1.5 lê a antiga e grava as duas |
  > | C. Canary da v2 | as duas | a "v1" do canary é a v1.5 | v2 lê `estado_saga` e grava as duas |
  > | D. Contract (`V12`) | só `estado_saga` | não pode mais voltar | v2.1 lê e grava só a nova |
  >
  > **Fase A, expand, na release v1.5.** O Job roda `flyway migrate` com o script:
  >
  > ```sql
  > -- V10__expand_estado_saga.sql
  > SET lock_timeout = '3s';
  > ALTER TABLE pedido ADD COLUMN estado_saga VARCHAR(40) NULL;
  > ```
  >
  > A coluna nasce sem valor padrão e aceitando vazio. No PostgreSQL, isso só altera a descrição da tabela, sem reescrever milhões de linhas, e termina em milissegundos. O `lock_timeout` diz: se não conseguir a trava da tabela em 3 segundos, desista. A 5.000 TPS, sempre há transações abertas, e um `ALTER` esperando trava todo mundo que chega depois dele. Se o Job desistir, ele tenta de novo, sem parar o checkout. As réplicas `v1` continuam funcionando, porque `SELECT status_pagamento ...` encontra a coluna. Em seguida, a `v1.5` entra com rolling update comum e passa a fazer escrita dupla: na mesma transação, cada mudança grava as duas colunas.
  >
  > **Fase B, migrate, ainda na v1.5.** Com a frota toda em `v1.5`, o Job copia os pedidos antigos. O Flyway não recebe `:inicio` e `:fim` num script SQL: esses parâmetros não existem para ele. O lote é uma procedure que o PostgreSQL executa, com `COMMIT` a cada 5.000 linhas. O arquivo `V11__backfill_estado_saga.sql.conf` leva `executeInTransaction=false`, senão o `COMMIT` de dentro da procedure é recusado.
  >
  > ```sql
  > -- V11__backfill_estado_saga.sql
  > CREATE OR REPLACE PROCEDURE pedido_backfill_estado_saga(batch_size int DEFAULT 5000)
  > LANGUAGE plpgsql AS $$
  > DECLARE n int;
  > BEGIN
  >   IF EXISTS (
  >     SELECT 1 FROM pedido
  >      WHERE estado_saga IS NULL
  >        AND status_pagamento NOT IN (
  >          'PENDENTE','AGUARDANDO_PAGAMENTO','AGUARDANDO_PIX','PAGO','CONCLUIDO',
  >          'CONFIRMADO','RESERVANDO_ESTOQUE','ESTORNANDO','ESTORNO_EM_REVISAO','CANCELADO')
  >   ) THEN
  >     RAISE EXCEPTION 'status_pagamento sem mapa para estado_saga';
  >   END IF;
  >   LOOP
  >     UPDATE pedido p SET estado_saga = CASE p.status_pagamento
  >       WHEN 'PENDENTE' THEN 'PENDENTE'
  >       WHEN 'AGUARDANDO_PAGAMENTO' THEN 'AGUARDANDO_PAGAMENTO'
  >       WHEN 'AGUARDANDO_PIX' THEN 'AGUARDANDO_PAGAMENTO'
  >       WHEN 'PAGO' THEN 'PAGO'
  >       WHEN 'CONCLUIDO' THEN 'CONCLUIDO'
  >       WHEN 'CONFIRMADO' THEN 'CONCLUIDO'
  >       WHEN 'RESERVANDO_ESTOQUE' THEN 'PENDENTE'
  >       WHEN 'ESTORNANDO' THEN 'ESTORNANDO'
  >       WHEN 'ESTORNO_EM_REVISAO' THEN 'ESTORNO_EM_REVISAO'
  >       WHEN 'CANCELADO' THEN CASE
  >         WHEN NOT EXISTS (SELECT 1 FROM pagamento g WHERE g.pedido_id = p.id AND g.capturado)
  >           THEN 'CANCELADO_SEM_COBRANCA'
  >         WHEN EXISTS (SELECT 1 FROM pagamento g WHERE g.pedido_id = p.id AND g.estorno_confirmado)
  >           THEN 'CANCELADO_COM_ESTORNO'
  >         ELSE 'ESTORNO_EM_REVISAO' END
  >     END
  >     WHERE p.id IN (
  >       SELECT id FROM pedido WHERE estado_saga IS NULL ORDER BY id LIMIT batch_size
  >     );
  >     GET DIAGNOSTICS n = ROW_COUNT;
  >     COMMIT;
  >     EXIT WHEN n = 0;
  >     PERFORM pg_sleep(0.05);
  >   END LOOP;
  > END $$;
  > CALL pedido_backfill_estado_saga(5000);
  > ```
  >
  > O `WHERE estado_saga IS NULL` torna o backfill seguro para repetir: se o Job cair no meio, a próxima chamada só toca o que falta. Cada lote termina no `COMMIT`, com pausa entre eles, e o Job para se o atraso das réplicas de leitura do banco passar do limite. O laboratório desta aula não roda essa procedure: o `V4` em `db/migration` é um `UPDATE` único, que o H2 em modo PostgreSQL aceita, e copia o valor antigo quando ele não é `CANCELADO` (o teste espera `CONFIRMADO` na coluna nova). Esse `V4` prova a separação do `CANCELADO` e a repetição sem efeito. Não prova o mapa canônico nem o lote com `COMMIT`. Uma linha cancelada, antes e depois:
  >
  > | id | status_pagamento | estado_saga |
  > | :--- | :--- | :--- |
  > | 30117 (antes) | CANCELADO | (vazio) |
  > | 30117 (depois) | CANCELADO | CANCELADO_COM_ESTORNO |
  >
  > Este pedido teve o Pix capturado e o estorno confirmado, então a coluna nova guarda o detalhe, e a antiga continua com o valor que a `v1.5` entende. Um pedido capturado sem estorno confirmado vira `ESTORNO_EM_REVISAO`, e o job de reconciliação da seção 3.2 resolve. Antes de seguir, duas conferências: `SELECT count(*) FROM pedido WHERE estado_saga IS NULL` tem de dar 0, e uma consulta compara as duas colunas pelo mapeamento e não pode achar divergência. Só então entra `ADD CONSTRAINT estado_saga_nn CHECK (estado_saga IS NOT NULL) NOT VALID` seguido de `VALIDATE CONSTRAINT`, que verifica sem bloquear as escritas.
  >
  > **Fase C, a v2 no canary.** A `v2` lê `estado_saga` e continua gravando as duas colunas, traduzindo. É ela que passa pelos degraus da seção 2.1. Um pedido criado agora pela `v2`, com o Pix gerado, fica assim:
  >
  > | status_pagamento (o que a v1.5 lê) | estado_saga (canônico) |
  > | :--- | :--- |
  > | AGUARDANDO_PIX | AGUARDANDO_PAGAMENTO |
  >
  > Se o webhook desse pedido cair numa réplica `v1.5`, ela lê `status_pagamento`, encontra `AGUARDANDO_PIX` e grava `PAGO` nas duas colunas (`PAGO` já é o nome canônico). Nenhuma das duas versões manda SQL para uma coluna que não existe. Um `CONFIRMADO` antigo não é copiado para `estado_saga`: o mapa da fase B já o levou a `CONCLUIDO`.
  >
  > **Fase D, contract, numa release futura.** Uma semana depois, fora do pico, a `v2.1` para de gravar `status_pagamento`. Quando ela estiver em 100% e a janela de rollback para a `v2` tiver passado, `V12__contract_drop_status_pagamento.sql` remove a coluna. A partir desse ponto, voltar para a `v1.5` não é mais possível. Essa decisão é tomada de forma consciente, registrada no PR, e nunca no meio de uma Black Friday. No projeto desta aula, o `V5` de contract fica numa pasta separada, que o Flyway não carrega, justamente para não ir junto por engano.
  >
  > Um script que já rodou nunca é editado. O Flyway grava uma soma de verificação (checksum) de cada script, e `flyway validate` falha se o arquivo mudar. Uma correção vira um script novo.
- **Garantia de Rollback Imediato:** Caso a versão `v2` apresente degradação grave de performance com 20% do tráfego na Black Friday, qual é o procedimento exato de rollback e por que o seu desenho de banco de dados garante que a versão `v1` voltará a assumir 100% da carga sem quebrar nem perder dados gravados pela `v2`?
  > **Decisão:** com 20% do tráfego na `v2` (cerca de 1.000 TPS de 5.000), duas leituras ruins abortam o rollout e devolvem 100% à `v1.5`, sem desfazer migração. Exemplo: a `v2` tem uma consulta sem índice, o p99 dela sobe de 180 ms para 900 ms e o da estável segue em 175 ms.
  >
  > 1. A análise lê o p99 da `v2`. Ele está mais de 20% acima da `v1.5`. A primeira leitura ruim é anotada.
  > 2. A leitura seguinte confirma. A análise aborta o rollout sozinha. Se o plantonista notar antes, ele pode rodar `kubectl argo rollouts abort checkout` e o efeito é o mesmo.
  > 3. **Segundos depois:** o peso da rota volta para 100% na `v1.5`. Nenhuma réplica nova precisa subir: as dez réplicas `v1.5` nunca saíram do ar, estavam aquecidas e dimensionadas para 100% da carga, porque o canary rodou além delas, e não no lugar delas.
  > 4. **Próximo minuto:** as réplicas `v2` recebem o sinal de desligamento, terminam as requisições em andamento (o desligamento gracioso do Spring espera até 30 s) e param de consumir do Kafka. As partições delas passam para os consumidores `v1.5`, que continuam do último ponto confirmado. Uma mensagem reprocessada é descartada pela tabela de idempotência.
  > 5. **Nenhuma migração é desfeita.** Não existe "migração de volta" no caminho de rollback. O banco atual, com as duas colunas, já é o banco da `v1.5`.
  > 6. **Depois:** o rollback fica registrado no histórico do repositório de deploy, o digest `sha256:9b2e...` é marcado como reprovado, e o time abre um post-mortem.
  >
  > **Por que nada quebra e nada se perde.** Pegue um pedido que a `v2` levou a `CONCLUIDO`. Primeiro, a coluna `status_pagamento` nunca saiu do banco enquanto a `v2` existia, então a `v1.5` não encontra o erro de coluna inexistente. Segundo, a `v2` gravou as duas colunas: `CONCLUIDO` em `estado_saga` e, na coluna antiga, o token que a `v1.5` entende (`CONFIRMADO`, se era esse o vocabulário dela). Terceiro, quando a `v2` cancelou como `CANCELADO_COM_ESTORNO`, também gravou `CANCELADO` na coluna antiga, e manteve o detalhe em `estado_saga`. Quarto, um pedido no meio da saga continua pelas transições condicionais, e as mensagens da `v2` só tinham campos opcionais a mais. Se algum pedido ficar parado por causa da troca, o job de prazos o encontra pelo prazo vencido, com o mesmo `message_id` do comando (apêndice), inclusive se estiver em `PAGO`.

---

## 3. Guardrails de Segurança, Governança e Validação da Release

### 3.1 Gates de Segurança e Guardrails no Processo de Entrega

- **Guardrails do Commit ao Deploy:** Quais portões automatizados de qualidade e segurança devem proteger a sua esteira ao longo do fluxo de desenvolvimento (desde o Pull Request até a publicação e promoção da imagem)?
  > Um gate (portão) é uma verificação automática que impede o próximo passo se falhar. Um PR com `V10__renomeia_status.sql`, o script do incidente, para no primeiro portão.
  >
  > **No Pull Request, com GitHub Actions.** O workflow roda `./mvnw -B verify` com JDK 21 e cache do Maven: compila, roda os testes unitários e de integração, e aplica as migrações num banco descartável. No mesmo PR rodam a busca de segredos esquecidos no código (gitleaks), a análise estática de segurança (CodeQL), o lint do Dockerfile (hadolint) e `flyway validate`. E existe uma regra feita para este incidente: um script que contenha `DROP`, `RENAME` ou `ALTER ... TYPE` reprova o PR, a menos que ele esteja marcado como fase de contract e prove que a release em produção já não lê aquela coluna. O PR para aqui, com a mensagem "migração destrutiva fora da fase de contract", e a mudança se divide em `V10` (add) e `V11` (backfill). A pasta `db/migration/` também exige revisão de alguém do time de dados (CODEOWNERS), e a `main` é protegida contra push direto.
  >
  > **No merge, no build único.** Depois do merge, a esteira confere a assinatura da imagem base distroless com `cosign verify`, para garantir que ela veio mesmo do Google. Depois constrói a imagem e gera o SBOM duas vezes. O SBOM (Software Bill of Materials) é a lista de ingredientes: cada biblioteca e versão que está dentro do artefato. Um vem do Maven, com `mvn cyclonedx:makeAggregateBom`, e lista os jars. Outro vem do Trivy, com `trivy image --format cyclonedx`, e lista também os pacotes do sistema e a JRE. Em seguida, `trivy image --exit-code 1 --severity CRITICAL --ignore-unfixed` falha a esteira se houver CVE crítica com correção disponível. CVE alta gera alerta e comentário, sem bloquear. Um segundo Trivy olha a configuração da imagem (usuário root, segredo em variável de ambiente). Foi esse gate que, no projeto desta aula, achou 16 CVEs críticas no Tomcat 10 e forçou a subida para Spring Boot 4.1.1 com Tomcat 11.0.25. O teste de fumaça da seção 3.2 também roda aqui. Só então a imagem é publicada com tag imutável, assinada com Cosign, e o SBOM é anexado a ela.
  >
  > **Em staging.** Os testes de coexistência da seção 3.2 rodam contra o mesmo digest.
  >
  > **Na promoção.** O PR de troca de digest no repositório de deploy só é aceito com os testes de staging verdes. No cluster de produção, o controle de admissão recusa imagem sem assinatura válida, referenciada por tag, configurada para rodar como root ou sem SBOM.
  >
  > **No rollout.** A análise automática do canary é o último portão.
  >
  > **Depois do deploy.** Todo dia, um scan cruza o SBOM das imagens em produção com as CVEs novas publicadas. Uma falha descoberta na terça numa biblioteca que já está em produção vira um alerta na terça, e não na próxima release.
- **Governança e Controle em Produção:** Quais *guardrails* de processo e arquitetura impedem que alterações manuais não rastreadas cheguem à produção e como equilibrar segurança rigorosa com agilidade de resposta durante incidentes críticos sem perder a trilha de auditoria?
  > **Por que o jar do backup subiu à mão.** No incidente, alguém conseguiu subir um binário de um backup direto em produção. Ninguém sabia de onde ele vinha nem quais testes ele tinha passado. Agora, produção só muda por GitOps: o estado desejado do cluster mora num repositório Git, e o Argo CD aplica esse estado e desfaz qualquer alteração manual que divergir dele. Ninguém tem `kubectl apply` nem permissão de escrita no registry com credencial pessoal. A esteira se autentica com uma identidade OIDC de curta duração, uma credencial temporária emitida para aquela execução e que expira sozinha. O usuário de banco da aplicação não pode alterar tabelas, então uma réplica nunca mais muda o schema no boot. E não existe build de produção: existe só a promoção de um digest já testado.
  >
  > **Rápido no incidente, sem perder a trilha.** Ninguém precisa pedir aprovação para o rollback, e isso é proposital. Voltar para um digest que já passou por todos os portões é a ação mais segura e está pré-aprovada, seja pelo abort do canary, seja pelo revert do commit no repositório de deploy. Um hotfix passa pela mesma esteira em modo rápido: os mesmos gates de segurança, revisão de uma pessoa do plantão e degraus de canary mais curtos. Ele nunca pula o scan nem a assinatura. Para o caso raro de precisar de acesso direto, existe o papel de emergência (break-glass): liberado por 1 hora, com justificativa obrigatória e alerta para o time de segurança, e cada comando fica no registro de auditoria. Tudo que for feito assim tem de virar PR depois, e o Argo CD continua mostrando a divergência até isso acontecer.

### 3.2 Estratégia de Testes da Imagem e da Migração

- **Validação Pré-Tráfego:** Passar nos testes unitários durante o build do código não prova que a imagem final do container sobe nem que a migração de banco é retrocompatível.
- **Plano de Testes na Pipeline:** Descreva como você testará automaticamente a imagem construída e validará a coexistência da migração de banco de dados na esteira antes de liberar o deploy para o tráfego real de clientes.
  > No incidente, todos os testes unitários passaram. Eles rodam sobre o jar, num banco criado do zero, com uma versão só. Nenhum deles montou a situação que derrubou o checkout: duas versões no mesmo banco. Os testes abaixo montam essa situação de propósito. Cada um diz o que faz e qual parte do incidente ele pegaria.
  >
  > **1. Teste de fumaça da imagem real.** O smoke test (teste de fumaça) é o teste mais simples: ligar e ver se sai fumaça. A esteira sobe o container a partir do digest publicado, e não do jar, com as mesmas regras de produção (usuário não root, disco só leitura e `/tmp` gravável) e com PostgreSQL e Kafka descartáveis. No script desta aula isso é `--read-only --tmpfs /tmp`, o equivalente do `emptyDir` da seção 1.1. Sem esse `/tmp`, a JVM em distroless não tem diretório temporário. Ela espera `/actuator/health/readiness` responder 200 em até 60 s, faz uma requisição de negócio, confere que `docker run --entrypoint sh` falha porque não há shell, e confere que o processo não é o usuário 0. No projeto desta aula, `scripts/smoke-test.sh` faz isso e imprime `SMOKE OK`. Ele pegaria uma imagem que não sobe, uma variável de ambiente esquecida ou uma imagem com root.
  >
  > **2. Teste da migração numa cópia do schema de produção, em PostgreSQL de verdade.** A esteira restaura a estrutura do banco de produção, preenchida com dados sintéticos no mesmo volume, aplica os scripts novos e mede a duração e as travas. Ela falha se um `ALTER` segurar a trava exclusiva da tabela por mais de 1 s ou reescrever a tabela. Ele pegaria uma migração que funciona num banco vazio e trava o checkout com 20 milhões de pedidos. Este portão **não foi executado** no laboratório desta aula: `desafio/README.md` registra que os testes usam H2 em modo PostgreSQL e que `lock_timeout` em PostgreSQL real não foi validado. O H2 não segura `AccessExclusiveLock` como o Postgres, então um teste verde no H2 não prova o limite de 1 s. O que o H2 mostrou está no item 3.
  >
  > **3. Teste de coexistência entre a versão atual e a nova, o teste que teria pegado o incidente.** Com o schema já migrado, a esteira sobe ao mesmo tempo a imagem que está em produção (o digest anterior) e a imagem nova, no mesmo banco e no mesmo tópico do Kafka. O tráfego é misturado: pedidos criados por uma versão e avançados pela outra. O teste passa só se nenhuma das duas registrar erro de SQL, se um pedido gravado por uma for lido corretamente pela outra, com `status_pagamento` e `estado_saga` coerentes pelo mapeamento, e se, ao derrubar a imagem nova no meio do tráfego, a antiga concluir todos os pedidos em andamento. Com o `RENAME` do incidente, a imagem antiga receberia `column "status_pagamento" does not exist` no primeiro pedido, e o PR de promoção nunca seria aberto. No projeto desta aula, `MigracaoExpandContractTest` reproduz isso com dois repositórios no mesmo banco, um legado que lê a coluna antiga e um novo que lê a coluna nova, e tem um teste que falha se algum script de `db/migration` contiver `DROP` ou `RENAME`.
  >
  > **4. Testes da saga com verificação negativa.** Verificação negativa quer dizer conferir também o que não pode acontecer, e não só o que deve acontecer. O PSP e o Estoque são simulados:
  >
  > - *Estoque esgota antes do Pix.* O teste espera `CANCELADO_SEM_COBRANCA` e falha se houver qualquer chamada de cobrança ou de estorno, ou se aparecer um `CONCLUIDO` sem reserva.
  > - *A reserva expira durante um Pix lento.* O teste espera `CANCELADO_COM_ESTORNO` e exatamente uma chamada de estorno ao PSP, mesmo com o evento de pagamento entregue duas vezes e o webhook repetido. Ele falha com zero ou com duas.
  > - *O PSP não responde.* O teste espera que a saga consulte o status do pagamento no PSP antes de decidir, e falha se ela estornar ou cancelar enquanto o status é desconhecido. Com a resposta "pago", a saga segue; com "não pago", cancela sem cobrança.
  > - *O Estoque não responde.* O teste espera a consulta da reserva pelo `orderId` antes de decidir. Se a reserva existe, segue; se não existe, cancela sem cobrança, porque a reserva acontece antes do Pix. Ele falha se o pedido for confirmado sem reserva ou se uma liberação de estoque for enviada para uma reserva desconhecida.
  > - *Mensagens fora de ordem.* Eventos do mesmo `orderId` gerados pela versão antiga e pela nova chegam trocados. A transição condicional rejeita o atrasado, e o estado final tem de ser o correto.
  >
  > **5. O job de reconciliação, também testado.** A cada 5 minutos, um job procura pedidos em `ESTORNO_EM_REVISAO`, `PAGAMENTO_EM_VERIFICACAO`, `PAGO` ou qualquer estado não final além do prazo. Para cada um, consulta o status real no parceiro e aplica a transição do apêndice: estorno confirmado vira `CANCELADO_COM_ESTORNO`; pagamento confirmado sem estoque gera o mesmo comando de estorno (mesmo `message_id`, chave `pagamento_id`); `PAGO` com reserva confirmada vira `CONCLUIDO`. As cinco tentativas abrem `ESTORNO_EM_REVISAO` e o chamado. 24 h sem confirmação abrem `ESTORNO_FALHOU`; o job segue uma vez por dia e ainda pode fechar sem uma segunda devolução. O teste: um pedido em `ESTORNO_EM_REVISAO`, com o PSP respondendo "estornado", sai para `CANCELADO_COM_ESTORNO` na execução seguinte, sem chamada nova de estorno.
  >
  > **6. O portão final.** Só com 1 a 5 verdes a esteira abre o PR de promoção com o digest testado. A partir daí, o canary da seção 2.1 faz a última verificação, agora com clientes reais e reversão automática.

---

## O que cada decisão custa

O PR com `RENAME` para no gate de migração. A mudança se divide em add e backfill, a `v1.5` entra antes, e a `v2` só vai ao canary com o digest que passou no teste de coexistência. Se o p99 subir no degrau de 20%, o tráfego volta para a `v1.5` em segundos, sem migração desfeita. A tabela resume o custo de cada escolha:

| Decisão | O que ganhamos | O que custa | O que descartamos e por quê |
| :--- | :--- | :--- | :--- |
| Distroless, não root, disco só leitura, base por digest | menos CVEs, nenhum shell para um invasor, imagem menor | diagnóstico depende de logs, traces e `kubectl debug`; a JVM precisa de `emptyDir` em `/tmp` | imagem JDK completa, com superfície grande |
| Digest imutável e GitOps | rollback em segundos e auditoria completa | retenção no registry e disciplina de PR | `:latest`, a causa do rollback impossível |
| Canary | só uma fração dos clientes vê o defeito; rollback pela rota | duas versões ao mesmo tempo exigem compatibilidade | Blue-Green, que dobra a frota no pico e não resolve o banco compartilhado |
| Expand and Contract em três releases | versões antiga e nova juntas; rollback sem mexer no banco | mais releases e escrita dupla temporária | rename em um passo, a causa da queda |
| Migração como Job antes do deploy | uma execução controlada, com `lock_timeout` | um passo a mais na esteira | Flyway no boot de cada réplica |

## Referências usadas

The Twelve-Factor App (fator III, configuração no ambiente); Martin Fowler, "ParallelChange", "CanaryRelease" e "BlueGreenDeployment"; documentação do Distroless (GoogleContainerTools), incluindo a verificação com `cosign`; documentação do Flyway (Redgate) e do Spring Boot sobre migrações; Docker, multi-stage builds; Trivy, varredura de vulnerabilidades, de configuração e geração de SBOM; CycloneDX Maven Plugin; Paketo Buildpacks (`BP_JVM_JLINK_ENABLED`); GitHub Actions para Java com Maven; Helm e Terraform para Infraestrutura como Código.
