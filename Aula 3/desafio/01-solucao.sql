-- Solução proposta do laboratório (rodar depois do 00-setup.sql).
-- Cada desafio: ANTES (plano original) -> índice -> DEPOIS (mesma consulta).
\pset pager off
SELECT setseed(0.42);
ANALYZE;
\echo '===== DESAFIO 1: ANTES ====='
SELECT customer_id AS cid FROM olist_orders LIMIT 1 \gset
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM olist_orders WHERE customer_id = :'cid';
CREATE INDEX idx_orders_customer ON olist_orders (customer_id);
\echo '===== DESAFIO 1: DEPOIS ====='
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM olist_orders WHERE customer_id = :'cid';

\echo '===== DESAFIO 2: ANTES ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_id, customer_id, order_status, order_purchase_timestamp FROM olist_orders
WHERE order_status = 'delivered' AND order_purchase_timestamp >= NOW() - INTERVAL '30 days'
ORDER BY order_purchase_timestamp DESC LIMIT 20;
\echo '----- 2b: índices isolados -----'
CREATE INDEX idx_orders_status ON olist_orders (order_status);
CREATE INDEX idx_orders_ts ON olist_orders (order_purchase_timestamp);
ANALYZE olist_orders;
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_id, customer_id, order_status, order_purchase_timestamp FROM olist_orders
WHERE order_status = 'delivered' AND order_purchase_timestamp >= NOW() - INTERVAL '30 days'
ORDER BY order_purchase_timestamp DESC LIMIT 20;
DROP INDEX idx_orders_ts;
\echo '----- 2c: composto (igualdade, depois intervalo/ordenação) -----'
CREATE INDEX idx_orders_status_ts ON olist_orders (order_status, order_purchase_timestamp DESC);
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_id, customer_id, order_status, order_purchase_timestamp FROM olist_orders
WHERE order_status = 'delivered' AND order_purchase_timestamp >= NOW() - INTERVAL '30 days'
ORDER BY order_purchase_timestamp DESC LIMIT 20;
\echo '----- 2d: ordem invertida (ts, status) -----'
DROP INDEX idx_orders_status_ts;
CREATE INDEX idx_orders_ts_status ON olist_orders (order_purchase_timestamp DESC, order_status);
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_id, customer_id, order_status, order_purchase_timestamp FROM olist_orders
WHERE order_status = 'delivered' AND order_purchase_timestamp >= NOW() - INTERVAL '30 days'
ORDER BY order_purchase_timestamp DESC LIMIT 20;
DROP INDEX idx_orders_ts_status;
CREATE INDEX idx_orders_status_ts ON olist_orders (order_status, order_purchase_timestamp DESC);

\echo '===== DESAFIO 3: ANTES ====='
SELECT seller_id AS sid FROM olist_order_items GROUP BY seller_id ORDER BY count(*) DESC LIMIT 1 \gset
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.order_id, o.order_purchase_timestamp, o.order_status, i.product_id, i.price, p.payment_type
FROM olist_order_items i JOIN olist_orders o ON i.order_id = o.order_id
JOIN olist_order_payments p ON o.order_id = p.order_id
WHERE i.seller_id = :'sid' ORDER BY o.order_purchase_timestamp DESC LIMIT 20;
CREATE INDEX idx_items_seller ON olist_order_items (seller_id);
\echo '===== DESAFIO 3: DEPOIS ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.order_id, o.order_purchase_timestamp, o.order_status, i.product_id, i.price, p.payment_type
FROM olist_order_items i JOIN olist_orders o ON i.order_id = o.order_id
JOIN olist_order_payments p ON o.order_id = p.order_id
WHERE i.seller_id = :'sid' ORDER BY o.order_purchase_timestamp DESC LIMIT 20;
\echo '----- 3b: FK não cria índice; DELETE no pai -----'
SELECT order_id AS oid FROM olist_orders LIMIT 1 \gset
SELECT indexrelid::regclass FROM pg_index WHERE indrelid IN ('olist_order_items'::regclass,'olist_order_payments'::regclass);
BEGIN; EXPLAIN (ANALYZE, BUFFERS) DELETE FROM olist_orders WHERE order_id = :'oid'; ROLLBACK;
-- Obs.: aqui as PKs compostas (order_id, ...) já começam por order_id, então servem à FK.

\echo '===== DESAFIO 4 ====='
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM olist_orders WHERE order_status = 'delivered';
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM olist_orders WHERE order_status = 'canceled';
SELECT order_status, round(100.0*count(*)/sum(count(*)) over (),1) AS pct FROM olist_orders GROUP BY 1;

\echo '===== DESAFIO 5 ====='
SELECT pg_size_pretty(pg_relation_size('olist_orders')) AS tamanho_tabela,
       pg_size_pretty(pg_indexes_size('olist_orders')) AS total_estruturas_auxiliares;
SELECT indexrelid::regclass AS indice, pg_size_pretty(pg_relation_size(indexrelid)) FROM pg_index WHERE indrelid='olist_orders'::regclass;
-- Experimento: PK aleatória (md5, ~UUIDv4) vs bigint sequencial, mesmo volume.
CREATE TABLE t_seq (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, x int);
CREATE TABLE t_rand (id varchar(32) PRIMARY KEY, x int);
\timing on
INSERT INTO t_seq (x) SELECT g FROM generate_series(1,300000) g;
INSERT INTO t_rand SELECT md5(random()::text), g FROM generate_series(1,300000) g;
\timing off
CREATE EXTENSION IF NOT EXISTS pgstattuple;
SELECT 't_seq' t, pg_size_pretty(pg_relation_size('t_seq_pkey')) tam, (pgstatindex('t_seq_pkey')).avg_leaf_density
UNION ALL SELECT 't_rand', pg_size_pretty(pg_relation_size('t_rand_pkey')), (pgstatindex('t_rand_pkey')).avg_leaf_density;
DROP TABLE t_seq, t_rand;

\echo '===== DESAFIO 6 ====='
SELECT calls, round(total_exec_time::numeric,1) total_ms, round(mean_exec_time::numeric,2) media_ms, rows,
       left(regexp_replace(query,'\s+',' ','g'),90) AS query
FROM pg_stat_statements WHERE query NOT LIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC LIMIT 5;
