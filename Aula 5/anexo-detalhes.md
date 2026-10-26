# Anexo da Aula 5 — SQL, degraus, gates

Aqui ficam os scripts, as tabelas e o custo de cada escolha. Estados do pedido: [apêndice da Aula 4](../Aula%204/apendice-maquina-de-estados.md).

## Imagem

```yaml
volumeMounts:
  - name: tmp
    mountPath: /tmp
volumes:
  - name: tmp
    emptyDir: {}
```

O `emptyDir` não é volume de dados do pedido. O processo continua sem ser administrador, sem shell e sem gravar no resto do disco.

Paketo Buildpacks (`mvn spring-boot:build-image`) gera uma imagem parecida sem Dockerfile. O Dockerfile ganhou porque a aula pede para mostrar cada estágio. Com `BP_JVM_JLINK_ENABLED=true` a JRE fica só com os módulos que o serviço usa. Tamanho medido de uma imagem antiga não é repetido aqui: não há artefato deste scan no repositório para conferir o número.

O digest da base está fixado no `desafio/Dockerfile`. A esteira roda `cosign verify` nessa base antes do build. Ela audita as duas camadas: dependências Java (SCA) e pacotes do sistema. O uid 65532 é o `nonroot` da imagem, e o `chown` das camadas copiadas usa o mesmo número.

<a id="promocao"></a>
## Promoção

```bash
docker build -t registry/checkout:1.5.0-a1b2c3d .
docker push registry/checkout:1.5.0-a1b2c3d
# o registry devolve o digest; os ambientes usam registry/checkout@sha256:...
```

`1.5.0` é a versão do `pom.xml` desta aula. `a1b2c3d` é o commit, exemplo de formato. O digest do exemplo (`sha256:9b2e7c...f41a`) ilustra a forma; o valor real é o que o registry devolve. O registry guarda pelo menos as últimas 10 versões que estiveram em produção. Senha e URL vêm de ConfigMap e Secret, descritos em Helm e Terraform. As etiquetas `org.opencontainers.image.revision` e `org.opencontainers.image.version` aparecem no `/actuator/info` e em cada linha de log.

## Canary

| Degrau | Tráfego na v2 | A 5.000 TPS | Duração |
| :--- | :--- | :--- | :--- |
| 1 | 1% | ~50 TPS | 10 min |
| 2 | 5% | ~250 TPS | 10 min |
| 3 | 20% | ~1.000 TPS | 15 min |
| 4 | 50% | ~2.500 TPS | 15 min |
| 5 | 100% | 5.000 TPS | fim |

Quatro motivos da convivência, em detalhe:

1. Banco: as duas versões funcionam com o schema da seção de migração.
2. API: a v2 só acrescenta campos opcionais. Nada some e nada muda de sentido nesta release. A flag `saga_estados_v2` fica só nos pods v2. Ligada, a v2 grava os doze estados em `estado_saga` e o token antigo em `status_pagamento`. A v1.5 continua lendo `status_pagamento`. O Argo Rollouts (a ferramenta que move a porcentagem do canary) já opera em 1% e 5%, com os mesmos sinais.
3. Mensagens: chave `orderId`. Quando o Kafka redistribui partições, pode entregar a mensagem duas vezes. O consumidor grava o `message_id` com `UNIQUE` e ignora o repetido. O reenvio do job usa esse mesmo `message_id` (apêndice), não um id novo.
4. Webhook do PSP: as duas conferem o HMAC e só mudam a linha se status ainda é `AGUARDANDO_CAPTURA` ou `EXPIRADO`. O teto de Pix em `AGUARDANDO_PAGAMENTO` é o medido na Aula 6. `UNIQUE (pagamento_id)` é a trava da tabela `estorno`, não deste webhook.

<a id="migracao"></a>
## Migração

| Fase | Colunas | v1 do enunciado | Quem grava as duas |
| :--- | :--- | :--- | :--- |
| Antes | `status_pagamento` | lê e grava a antiga | ninguém |
| A. Expand (`V10`), release v1.5 | as duas; `estado_saga` aceita vazio | ainda no ar, só a antiga | v1.5 lê a antiga e grava as duas |
| B. Migrate (`V11`), ainda v1.5 | as duas, preenchidas | saiu: a frota já é v1.5 | v1.5 |
| C. Canary da v2 | as duas | o papel de "versão antiga" junto da v2 é a v1.5 | v2 lê `estado_saga` e grava as duas |
| D. Contract (`V12`), release futura | só `estado_saga` | voltar para a v1.5 deixa de ser possível | v2.1 lê e grava só a nova |

```sql
-- V10__expand_estado_saga.sql
SET lock_timeout = '3s';
ALTER TABLE pedidos ADD COLUMN estado_saga VARCHAR(40) NULL;
```

A 5.000 TPS sempre há transações abertas. Um `ALTER` esperando trava quem chega depois. Se o Job não conseguir a trava em 3 s, desiste e tenta de novo, sem parar o checkout.

<a id="mapa-de-pares"></a>
## Mapa de pares

O backfill roda no banco de cada serviço. O Checkout não lê `pagamento` nem `estorno`. O Pagamento devolve `(order_id, destino)` e o job copia em `legado_cancelado`. Sem destino, `estado_saga` fica nulo e o lote falha. `CANCELADO_SEM_COBRANCA` só entra com destino explícito, inclusive a marca de consulta concluída sem linha. `status_pagamento` é a coluna antiga. `estado_saga` é a nova. Na Aula 4 o estado vivo se chama `pedidos.status`.

Zero linha fora do mapa: um token que não está na tabela abaixo faz o script lançar exceção. Os doze estados do apêndice entram no mapa como o mesmo token, então uma linha que já os carrega não dispara essa exceção.

| Coluna antiga | `estado_saga` |
| :--- | :--- |
| `AGUARDANDO_PIX` | `AGUARDANDO_PAGAMENTO` |
| `CONFIRMADO` | `CONCLUIDO` |
| `RESERVANDO_ESTOQUE` | `PENDENTE` |
| `CANCELADO` sem captura, com destino explícito ou marca de consulta concluída sem linha | `CANCELADO_SEM_COBRANCA` |
| `CANCELADO` com estorno `CONCLUIDO` | `CANCELADO_COM_ESTORNO` |
| `CANCELADO` com estorno `FALHOU` | `ESTORNO_FALHOU` |
| `CANCELADO` com captura e sem estorno concluído nem falho | `ESTORNO_EM_REVISAO` |
| `CANCELADO` com `pagamento.status = PENDENTE` | `PAGAMENTO_EM_VERIFICACAO` |
| `PENDENTE`, `AGUARDANDO_PAGAMENTO`, `PAGO`, `CONCLUIDO`, `ESTORNANDO`, `ESTORNO_EM_REVISAO` | o mesmo token |
| `ESTOQUE_EM_VERIFICACAO`, `PAGAMENTO_EM_VERIFICACAO`, `CONFIRMACAO_EM_VERIFICACAO`, `ESTORNO_FALHOU`, `CANCELADO_SEM_COBRANCA`, `CANCELADO_COM_ESTORNO` | o mesmo token (já é um dos doze) |

```sql
-- Banco do Checkout. Só a tabela pedidos.
-- CANCELADO fica de fora: o destino chega em legado_cancelado.
-- executeInTransaction=false no .conf, senão o COMMIT interno é recusado.
CREATE OR REPLACE PROCEDURE pedidos_backfill_nomes(batch_size int DEFAULT 5000)
LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  IF EXISTS (
    SELECT 1 FROM pedidos
     WHERE estado_saga IS NULL
       AND status_pagamento NOT IN (
         'PENDENTE','ESTOQUE_EM_VERIFICACAO','AGUARDANDO_PAGAMENTO','AGUARDANDO_PIX',
         'PAGAMENTO_EM_VERIFICACAO','PAGO','CONFIRMACAO_EM_VERIFICACAO','CONCLUIDO',
         'CONFIRMADO','RESERVANDO_ESTOQUE','CANCELADO_SEM_COBRANCA','ESTORNANDO',
         'ESTORNO_EM_REVISAO','ESTORNO_FALHOU','CANCELADO_COM_ESTORNO','CANCELADO')
  ) THEN
    RAISE EXCEPTION 'status_pagamento sem mapa para estado_saga';
  END IF;
  LOOP
    UPDATE pedidos p SET estado_saga = CASE p.status_pagamento
      WHEN 'PENDENTE' THEN 'PENDENTE'
      WHEN 'ESTOQUE_EM_VERIFICACAO' THEN 'ESTOQUE_EM_VERIFICACAO'
      WHEN 'AGUARDANDO_PAGAMENTO' THEN 'AGUARDANDO_PAGAMENTO'
      WHEN 'AGUARDANDO_PIX' THEN 'AGUARDANDO_PAGAMENTO'
      WHEN 'PAGAMENTO_EM_VERIFICACAO' THEN 'PAGAMENTO_EM_VERIFICACAO'
      WHEN 'PAGO' THEN 'PAGO'
      WHEN 'CONFIRMACAO_EM_VERIFICACAO' THEN 'CONFIRMACAO_EM_VERIFICACAO'
      WHEN 'CONCLUIDO' THEN 'CONCLUIDO'
      WHEN 'CONFIRMADO' THEN 'CONCLUIDO'
      WHEN 'RESERVANDO_ESTOQUE' THEN 'PENDENTE'
      WHEN 'CANCELADO_SEM_COBRANCA' THEN 'CANCELADO_SEM_COBRANCA'
      WHEN 'ESTORNANDO' THEN 'ESTORNANDO'
      WHEN 'ESTORNO_EM_REVISAO' THEN 'ESTORNO_EM_REVISAO'
      WHEN 'ESTORNO_FALHOU' THEN 'ESTORNO_FALHOU'
      WHEN 'CANCELADO_COM_ESTORNO' THEN 'CANCELADO_COM_ESTORNO'
    END
    WHERE p.id IN (
      SELECT id FROM pedidos
       WHERE estado_saga IS NULL AND status_pagamento <> 'CANCELADO'
       ORDER BY id LIMIT batch_size
    );
    GET DIAGNOSTICS n = ROW_COUNT;
    COMMIT;
    EXIT WHEN n = 0;
    PERFORM pg_sleep(0.05);
  END LOOP;
  -- Sem destino o lote falha. Não inventa CANCELADO_SEM_COBRANCA.
  UPDATE pedidos p
     SET estado_saga = c.destino
    FROM legado_cancelado c
   WHERE p.id = c.order_id
     AND p.status_pagamento = 'CANCELADO'
     AND p.estado_saga IS NULL
     AND c.destino IN ('CANCELADO_SEM_COBRANCA','CANCELADO_COM_ESTORNO','ESTORNO_EM_REVISAO','ESTORNO_FALHOU','PAGAMENTO_EM_VERIFICACAO');
  IF EXISTS (
    SELECT 1 FROM pedidos
     WHERE status_pagamento = 'CANCELADO' AND estado_saga IS NULL
  ) THEN
    RAISE EXCEPTION 'CANCELADO sem destino do Pagamento';
  END IF;
END $$;
```

```sql
-- Banco do Pagamento. Devolve (order_id, destino). Não grava no Checkout.
-- ids = pedidos CANCELADO. O job copia o retorno em legado_cancelado.
-- Id do lote sem linha, com a consulta concluída, ganha marca explícita.
-- Se o banco do Pagamento não responde, o job não grava e o Checkout falha o lote.
CREATE OR REPLACE FUNCTION pagamento_classifica_cancelado(ids uuid[])
RETURNS TABLE (order_id uuid, destino text)
LANGUAGE sql AS $$
  SELECT g.order_id,
    CASE
      WHEN g.status = 'PENDENTE' THEN 'PAGAMENTO_EM_VERIFICACAO'
      WHEN g.status = 'APROVADO' AND EXISTS (
        SELECT 1 FROM estorno e
         WHERE e.pagamento_id = g.id AND e.status = 'CONCLUIDO')
        THEN 'CANCELADO_COM_ESTORNO'
      WHEN g.status = 'APROVADO' AND EXISTS (
        SELECT 1 FROM estorno e
         WHERE e.pagamento_id = g.id AND e.status = 'FALHOU')
        THEN 'ESTORNO_FALHOU'
      WHEN g.status = 'APROVADO' THEN 'ESTORNO_EM_REVISAO'
      ELSE 'CANCELADO_SEM_COBRANCA'
    END
  FROM pagamento g
  WHERE g.order_id = ANY (ids);
$$;
```

```sql
-- Job, depois da função. Marca só o id consultado que não tinha linha.
INSERT INTO legado_cancelado (order_id, destino)
SELECT f.order_id, f.destino FROM pagamento_classifica_cancelado(:ids) f;
INSERT INTO legado_cancelado (order_id, destino)
SELECT i.id, 'CANCELADO_SEM_COBRANCA'
  FROM unnest(:ids) AS i(id)
 WHERE NOT EXISTS (SELECT 1 FROM pagamento g WHERE g.order_id = i.id);
```

O laboratório não roda essa função. O `V4` do H2 ainda copia `CONFIRMADO`, porque o teste espera esse token, e não prova este mapa.

Antes de seguir: `SELECT count(*) FROM pedidos WHERE estado_saga IS NULL` tem de dar 0, e uma consulta compara as duas colunas pelo mapeamento. Só então `ADD CONSTRAINT estado_saga_nn CHECK (estado_saga IS NOT NULL) NOT VALID` e `VALIDATE CONSTRAINT`, que confere sem bloquear as escritas.

Três escritas, cada uma com a sua versão e o seu ponto de partida. Nenhuma manda SQL para uma coluna que não existe.

(a) Backfill de nome, na migração, partindo da coluna antiga já gravada pela v1.5. `AGUARDANDO_PIX` vira `AGUARDANDO_PAGAMENTO` em `estado_saga`. `CONFIRMADO` vira `CONCLUIDO`.

(b) Webhook numa réplica v1.5, partindo de `AGUARDANDO_PIX`. Onde o nome é o mesmo nos dois vocabulários (ex.: `PAGO`), `estado_saga` e `status_pagamento` recebem o mesmo valor. "Token antigo só em `status_pagamento`" vale só para os pares que mudam de nome: `CONFIRMADO`/`CONCLUIDO`, `AGUARDANDO_PIX`/`AGUARDANDO_PAGAMENTO`, e `CANCELADO` com o destino explícito do mapa. A escrita viva da conclusão continua em (c): `estado_saga = CONCLUIDO` e `status_pagamento = CONFIRMADO`.

(c) Conclusão, na v1.5 e na v2, partindo do pedido que acabou de ser pago. `estado_saga` fica `CONCLUIDO` e `status_pagamento` fica `CONFIRMADO`. A v1.5 não regrava o token legado em `estado_saga`.

Um script que já rodou nunca é editado. O Flyway guarda o checksum, e `flyway validate` falha se o arquivo mudar. Correção vira script novo. O contract (`V5` no laboratório, `V12` na sequência de produção) fica fora da pasta que o Flyway carrega no pico.

## Rollback

1. A análise lê o p99 da v2. Ele está mais de 20% acima da v1.5. A primeira leitura ruim é anotada.
2. A leitura seguinte confirma. A análise aborta. `kubectl argo rollouts abort checkout` tem o mesmo efeito se alguém notar antes.
3. Segundos depois, o peso da rota volta para 100% na v1.5. Nenhuma réplica nova precisa subir.
4. No minuto seguinte, as réplicas v2 recebem o sinal de desligamento, terminam as requisições em andamento (até 30 s) e param de consumir o Kafka. As partições passam para a v1.5, do último ponto confirmado.
5. Nenhuma migração é desfeita. O banco com as duas colunas já é o banco da v1.5.
6. O rollback fica no histórico do repositório de deploy e o digest da v2 é marcado como reprovado.

Pedido que a v2 levou a `CONCLUIDO`: a coluna antiga nunca saiu; a v2 gravou `CONCLUIDO` na nova e o token da v1.5 na antiga. Cancelamento `CANCELADO_COM_ESTORNO` na nova convive com `CANCELADO` na antiga, e o detalhe permanece em `estado_saga`. Pedido no meio da saga segue pelas transições condicionais. Mensagens da v2 só tinham campos opcionais a mais.

## Gates

| Momento | O que barra |
| :--- | :--- |
| Pull request | testes, gitleaks, CodeQL, hadolint, `flyway validate`, proibição de `DROP`/`RENAME`/`ALTER TYPE` fora do contract, CODEOWNERS em `db/migration/` |
| Merge | `cosign verify` da base, build único, SBOM CycloneDX e SBOM Trivy, Trivy `CRITICAL --ignore-unfixed` com exit 1, Trivy de configuração, smoke |
| Staging | coexistência no mesmo digest |
| Promoção | PR do digest só com staging verde; admission recusa tag, root, imagem sem assinatura ou sem SBOM |
| Rollout | análise do canary |
| Todo dia | scan do SBOM das imagens em produção contra CVEs novas |

Tomcat: o `pom.xml` fixa `<tomcat.version>11.0.25</tomcat.version>` em cima do Spring Boot 4.1.1. O gate recusa CVE crítica que já tem correção publicada.

<a id="governanca"></a>
## Governança

Hotfix: mesmos gates, revisão de uma pessoa do plantão, degraus de canary mais curtos. Sem pular scan nem assinatura.

Break-glass: 1 hora, justificativa, alerta ao time de segurança, cada comando no registro de auditoria, PR depois. O Argo CD mostra a divergência até o PR entrar.

## Testes

1. Smoke da imagem real, a partir do digest, com as regras de produção. Pegaria imagem que não sobe, variável esquecida ou processo root. `scripts/smoke-test.sh` imprime `SMOKE OK`.
2. Migração numa cópia do schema de produção, em PostgreSQL de verdade, com dados sintéticos no mesmo volume. Falha se um `ALTER` segurar a trava exclusiva por mais de 1 s ou reescrever a tabela. Este portão não foi executado no laboratório: o README registra H2 em modo PostgreSQL. O H2 não prova o limite de 1 s.
3. Coexistência. Com o `RENAME` do incidente, a imagem antiga receberia `column "status_pagamento" does not exist` no primeiro pedido, e o PR de promoção não abriria. `MigracaoExpandContractTest` reproduz os dois repositórios e recusa `DROP` ou `RENAME` em `db/migration`.
4. Saga com verificação negativa. Estoque esgota antes do Pix: `CANCELADO_SEM_COBRANCA`, e falha se houver cobrança, estorno ou `CONCLUIDO` sem reserva. Reserva expira durante um Pix lento: `CANCELADO_COM_ESTORNO` e exatamente uma devolução, mesmo com evento e webhook repetidos. PSP mudo: consulta o status antes de decidir, e falha se estornar ou cancelar enquanto o status é desconhecido. Estoque mudo: consulta a reserva pelo `orderId`; sem reserva, cancela sem cobrança. Mensagens fora de ordem: atrasado que mudaria o pedido para `CONCLUIDO` ou `CONFIRMADO` é rejeitado. A exceção do Pix atrasado está na [regra única](../Aula%204/apendice-maquina-de-estados.md#linha-de-pagamento).
5. Três relógios. Falha 1 espera 1 s, falha 2 espera 5 s, falha 3 espera 30 s, falha 4 espera 2 min, e a falha 5 abre a revisão. Poll é uma consulta repetida, curta o bastante para a espera de 1 s. Não há quinta espera. `PAGO` e os outros passos não finais usam `prazo_passo` (ex.: 30 s) e ficam fora do job de 5 min. Esse job de 5 min só olha a revisão de 24 h (`ESTORNO_EM_REVISAO`) e a consulta diária. 24 h sem confirmação abrem `ESTORNO_FALHOU`; a consulta diária ainda pode fechar sem uma segunda devolução. O teste: pedido em `ESTORNO_EM_REVISAO` com PSP "estornado" sai para `CANCELADO_COM_ESTORNO` sem chamada nova.
6. Portão final: só com 1 a 5 verdes a esteira abre o PR de promoção. O canary faz a última verificação, com clientes reais e reversão automática.

## Custo de cada escolha

| Decisão | O que ganhamos | O que custa | O que fica de fora |
| :--- | :--- | :--- | :--- |
| Distroless, não root, disco só leitura, base por digest | menos pacotes, nenhum shell, imagem reprodutível | diagnóstico por log, trace e `kubectl debug`; a JVM precisa de `emptyDir` em `/tmp` | imagem JDK completa na execução |
| Digest imutável e GitOps | rollback para o binário testado, com auditoria | retenção no registry e disciplina de PR | `:latest` |
| Canary | só uma fração vê o defeito; rollback pela rota | duas versões ao mesmo tempo exigem compatibilidade | blue-green, que dobra a frota no pico |
| Expand/contract em três releases | v1 e v2 juntas; rollback sem mexer no banco | mais releases e escrita dupla temporária | rename em um passo |
| Migração como Job antes do deploy | uma execução, com `lock_timeout` | um passo a mais na esteira | Flyway no boot de cada réplica |
