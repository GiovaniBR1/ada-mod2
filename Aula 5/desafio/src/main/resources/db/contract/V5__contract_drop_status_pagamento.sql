-- FASE 3 (CONTRACT): NAO fica em db/migration de proposito.
-- So entra numa release futura, depois que a v2.1 parou de gravar status_pagamento,
-- a v1 foi desativada e a janela de rollback passou. A partir daqui nao ha volta para a v1.
ALTER TABLE pedido DROP COLUMN status_pagamento;
