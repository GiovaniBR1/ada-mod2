# Perguntas de Risco: Aula 02 - Checkout e Pagamento Externo

**Cenário.** A API de Checkout precisa aguentar 10.000 usuários simultâneos e, para fechar cada compra, chama um Parceiro de Pagamento externo (ex.: Cielo, Stripe) que é instável: às vezes fica lento, às vezes devolve erro, às vezes derruba a conexão. O cliente (app ou site) pode reenviar o mesmo pedido quando a resposta demora. O dinheiro só pode sair uma vez, e uma falha nunca pode aparecer como compra aprovada.

1. **E se** der timeout na chamada ao parceiro depois que ele já cobrou o cartão, e o retry cobrar o cliente duas vezes?
   - Por que é arriscado: o timeout é ambíguo; não sabemos se a cobrança aconteceu. Repetir como se fosse nova vira cobrança dupla; desistir como se tivesse falhado deixa uma cobrança sem pedido.

2. **E se** o banco de dados cair na hora de registrar o estado de falha (o estado do Circuit Breaker ou o resultado do pagamento)?
   - Por que é arriscado: se o estado mora no banco, a proteção cai junto com ele. E se o parceiro aprovou e não conseguimos gravar, o cliente pagou e o sistema não sabe.

3. **E se** o cliente reenviar o mesmo checkout (duplo clique ou retry do app) e as duas requisições, com a mesma `Idempotency-Key`, chegarem ao mesmo tempo em réplicas diferentes?
   - Por que é arriscado: "confere se a chave existe, depois grava" em dois passos deixa as duas passarem pelo `if` e chamarem o parceiro, cada uma achando que é a primeira.
