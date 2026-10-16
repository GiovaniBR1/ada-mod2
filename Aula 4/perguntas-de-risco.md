# Perguntas de Risco: Aula 04 - Saga da Compra

**Cenário.** O checkout agora são três serviços com bancos separados (Checkout, Pagamento, Estoque) a 5.000 compras/s. A Saga é orquestrada pelo Checkout, os eventos saem pela outbox e o estorno do Pix é a compensação. O cliente nunca pode ficar cobrado sem produto e sem devolução.

1. **E se** a confirmação do Pix chegar **depois** de o job de prazos já ter cancelado o pedido por falta de pagamento?
   - Por que é arriscado: o pedido está `CANCELADO` e a reserva liberada, mas o dinheiro entrou. Se o evento for simplesmente ignorado como "atrasado", o cliente fica cobrado sem pedido.

2. **E se** o PSP fizer a devolução, mas a resposta se perder no caminho e o Pagamento tentar estornar de novo?
   - Por que é arriscado: se a segunda tentativa não usar a mesma chave de idempotência, o cliente recebe R$ 6.000 de volta por uma TV de R$ 3.000; se o Pagamento desistir, o pedido fica para sempre em `ESTORNANDO`.

3. **E se** a projeção de "Meus Pedidos" receber `PedidoConcluido` antes de `PagamentoAprovado` (eventos fora de ordem ou reprocessados)?
   - Por que é arriscado: a tela mostra "Confirmado" e, segundos depois, volta para "Pago, confirmando estoque". O cliente acha que a compra foi desfeita e abre chamado, ou tenta comprar de novo.
