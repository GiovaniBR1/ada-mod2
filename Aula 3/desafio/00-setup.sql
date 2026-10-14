CREATE TABLE olist_orders (
    order_id VARCHAR(32) PRIMARY KEY,
    customer_id VARCHAR(32) NOT NULL,
    order_status VARCHAR(20) NOT NULL,
    order_purchase_timestamp TIMESTAMP NOT NULL,
    order_approved_at TIMESTAMP,
    order_delivered_carrier_date TIMESTAMP,
    order_delivered_customer_date TIMESTAMP,
    order_estimated_delivery_date TIMESTAMP
);

CREATE TABLE olist_order_items (
    order_id VARCHAR(32) NOT NULL,
    order_item_id INT NOT NULL,
    product_id VARCHAR(32) NOT NULL,
    seller_id VARCHAR(32) NOT NULL,
    price NUMERIC(10, 2) NOT NULL,
    freight_value NUMERIC(10, 2) NOT NULL,
    PRIMARY KEY (order_id, order_item_id),
    CONSTRAINT fk_order_items_orders FOREIGN KEY (order_id) REFERENCES olist_orders (order_id) ON DELETE CASCADE
);

CREATE TABLE olist_order_payments (
    order_id VARCHAR(32) NOT NULL,
    payment_sequential INT NOT NULL,
    payment_type VARCHAR(20) NOT NULL,
    payment_installments INT NOT NULL,
    payment_value NUMERIC(10, 2) NOT NULL,
    PRIMARY KEY (order_id, payment_sequential),
    CONSTRAINT fk_order_payments_orders FOREIGN KEY (order_id) REFERENCES olist_orders (order_id) ON DELETE CASCADE
);

-- 100.000 pedidos
INSERT INTO olist_orders (
    order_id, customer_id, order_status, 
    order_purchase_timestamp, order_estimated_delivery_date
)
SELECT 
    md5(random()::text),
    md5((random() * 10000)::int::text),
    CASE WHEN random() < 0.90 THEN 'delivered' ELSE (ARRAY['shipped', 'canceled', 'invoiced'])[floor(random() * 3 + 1)] END,
    NOW() - (random() * 90) * INTERVAL '1 day',
    NOW() + (random() * 15) * INTERVAL '1 day'
FROM generate_series(1, 100000);

-- 200.000 itens (2 itens por pedido)
INSERT INTO olist_order_items (
    order_id, order_item_id, product_id, seller_id, price, freight_value
)
SELECT 
    o.order_id,
    item_seq,
    md5((random() * 5000)::int::text),
    md5((random() * 500)::int::text),
    (random() * 300 + 15)::numeric(10, 2),
    (random() * 40 + 10)::numeric(10, 2)
FROM olist_orders o
CROSS JOIN generate_series(1, 2) AS item_seq;

-- 100.000 pagamentos
INSERT INTO olist_order_payments (
    order_id, payment_sequential, payment_type, payment_installments, payment_value
)
SELECT 
    o.order_id,
    1,
    (ARRAY['credit_card', 'boleto', 'pix', 'voucher'])[floor(random() * 4 + 1)],
    floor(random() * 6 + 1)::int,
    (random() * 600 + 30)::numeric(10, 2)
FROM olist_orders o;

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Limpa os registros de carga para monitorar exclusivamente as queries dos desafios:
SELECT pg_stat_statements_reset();

