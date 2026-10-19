CREATE TABLE pedido (
    id               BIGINT PRIMARY KEY,
    valor_centavos   BIGINT      NOT NULL,
    status_pagamento VARCHAR(30) NOT NULL
);
CREATE TABLE pagamento (
    pedido_id         BIGINT PRIMARY KEY REFERENCES pedido(id),
    capturado         BOOLEAN NOT NULL,
    estorno_confirmado BOOLEAN NOT NULL
);
