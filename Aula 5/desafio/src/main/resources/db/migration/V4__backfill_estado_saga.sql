-- FASE 2 (MIGRATE/BACKFILL): idempotente (so linhas com estado_saga nulo).
-- Separa o CANCELADO antigo em cancelado sem cobranca, com estorno e estorno em revisao.
-- Em producao: em lotes por faixa de id, fora deste script versionado.
UPDATE pedido p SET estado_saga = CASE
    WHEN p.status_pagamento <> 'CANCELADO' THEN p.status_pagamento
    WHEN NOT EXISTS (SELECT 1 FROM pagamento g WHERE g.pedido_id = p.id AND g.capturado) THEN 'CANCELADO_SEM_COBRANCA'
    WHEN EXISTS (SELECT 1 FROM pagamento g WHERE g.pedido_id = p.id AND g.estorno_confirmado) THEN 'CANCELADO_COM_ESTORNO'
    ELSE 'ESTORNO_EM_REVISAO'
END
WHERE p.estado_saga IS NULL;
