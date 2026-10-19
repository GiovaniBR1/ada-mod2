-- So para testes (src/test/resources): o Spring Boot nao empacota este arquivo no jar nem na imagem.
-- Dados de exemplo cobrindo os casos ambiguos do CANCELADO antigo.
INSERT INTO pedido VALUES (1, 10000, 'CONFIRMADO');
INSERT INTO pedido VALUES (2, 5000,  'CANCELADO');   -- nada cobrado
INSERT INTO pedido VALUES (3, 7000,  'CANCELADO');   -- cobrado e estornado
INSERT INTO pedido VALUES (4, 9000,  'CANCELADO');   -- cobrado, estorno sem confirmacao
INSERT INTO pedido VALUES (5, 3000,  'PENDENTE');
INSERT INTO pagamento VALUES (1, TRUE,  FALSE);
INSERT INTO pagamento VALUES (3, TRUE,  TRUE);
INSERT INTO pagamento VALUES (4, TRUE,  FALSE);
