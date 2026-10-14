package br.com.ada.pagamentos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Modelos mínimos (records = classes imutáveis só de dados, como @dataclass(frozen=True) do Python). */
final class Dtos {
    private Dtos() {}

    /** Corpo que o consumidor manda para a nossa API. A chave vem no cabeçalho Idempotency-Key. */
    record PagamentoRequest(@NotBlank String id, @NotNull @Positive BigDecimal valor) {}

    /** Resposta da nossa API: só id e status. */
    record PagamentoResponse(String id, String status) {}

    /**
     * Pedido enviado ao PARCEIRO. A idempotencyKey é obrigatória no record e o cliente HTTP
     * sempre a coloca no cabeçalho Idempotency-Key (nunca no corpo).
     */
    record CobrancaParceiroRequest(String idempotencyKey, String id, BigDecimal valor) {
        CobrancaParceiroRequest {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey é obrigatória");
            }
        }
        /** O que vai no corpo JSON (sem a chave). */
        Corpo corpo() { return new Corpo(id, valor); }
        record Corpo(String id, BigDecimal valor) {}
    }

    /** Resposta do parceiro. */
    record CobrancaParceiroResponse(String id, String status) {}
}
