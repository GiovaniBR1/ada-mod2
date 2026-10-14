# Perguntas de Risco: Aula 03 - Banco de Dados do Checkout

**Cenário.** É o mesmo checkout da Aula 2, agora na Black Friday. A borda aguenta o tráfego, e o gargalo passou para a única instância do PostgreSQL: cada compra grava a chave de idempotência, reserva o estoque do produto, cria o pedido e o pagamento. Centenas de clientes disputam as últimas unidades do mesmo produto em promoção, e a consulta de pedidos sem índice já esgotou o pool de conexões com o banco (p99 de 4,8 s). O estoque nunca pode ficar negativo, um pagamento só fecha uma vez e uma falha nunca vira "aprovado".

1. **E se** a resposta atrasada do parceiro e o job de reconciliação tentarem fechar o **mesmo** pagamento `PENDENTE` ao mesmo tempo, um como `APROVADO` e o outro como `EXPIRADO`?
   - Por que é arriscado: os dois leem `PENDENTE`, os dois acham que podem decidir, e quem grava por último vence (*lost update*). O cliente pode ter sido cobrado e o pedido aparecer expirado, ou o estoque ser devolvido de uma compra paga.

2. **E se** o pagamento for recusado ou expirar **depois** que a última unidade do produto já foi reservada para ele?
   - Por que é arriscado: a unidade fica presa a uma compra que não vai acontecer. Na Black Friday, o produto aparece "esgotado" com estoque na prateleira; se a devolução for feita sem olhar o estado, ela pode devolver duas vezes e criar estoque que não existe.

3. **E se** a migration que cria o índice para resolver o incidente (pedidos por `customer_id` e status) rodar **durante o pico** e travar as gravações da tabela de pedidos?
   - Por que é arriscado: um `CREATE INDEX` comum bloqueia `INSERT`, `UPDATE` e `DELETE` na tabela até terminar. O remédio do incidente vira um novo incidente: o checkout para de gravar pedidos e o pool enche de novo.
