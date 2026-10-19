-- FASE 1 (EXPAND): so adiciona. Nullable e sem default: nao reescreve a tabela e nao quebra a v1.
-- Em PostgreSQL, rodar com: SET lock_timeout = '3s';
ALTER TABLE pedido ADD COLUMN estado_saga VARCHAR(40) NULL;
