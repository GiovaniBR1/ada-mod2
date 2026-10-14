package br.com.ada.pagamentos;

import br.com.ada.pagamentos.Dtos.PagamentoRequest;
import br.com.ada.pagamentos.Dtos.PagamentoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PagamentoController {

    private final PagamentoService service;

    public PagamentoController(PagamentoService service) { this.service = service; }

    @Operation(summary = "Cria um pagamento no parceiro, com idempotência e proteção contra falhas")
    @ApiResponse(responseCode = "201", description = "Parceiro respondeu; status final dele",
            content = @Content(schema = @Schema(implementation = PagamentoResponse.class)))
    @ApiResponse(responseCode = "202", description = "Fallback: parceiro lento ou com erro ambíguo. Pagamento PENDENTE; a reconciliação fecha depois. Reenviar com a mesma chave devolve esta resposta.",
            content = @Content(schema = @Schema(implementation = PagamentoResponse.class)))
    @ApiResponse(responseCode = "400", description = "Sem Idempotency-Key ou corpo inválido (Problem Details)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Mesma Idempotency-Key ainda em processamento",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Idempotency-Key reutilizada com outro corpo",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "O parceiro pode ter cobrado e a contingência falhou ao registrar PENDENTE",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Parceiro indisponível e NADA foi cobrado (circuito aberto, conexão recusada, 429/503 do parceiro). Tente de novo com a mesma chave.",
            headers = @Header(name = "Retry-After", description = "Segundos até tentar de novo"),
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/pagamentos")
    public ResponseEntity<PagamentoResponse> pagar(
            @Parameter(in = ParameterIn.HEADER, required = true, description = "UUID gerado pelo cliente por intenção de pagamento")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PagamentoRequest request) {
        var r = service.pagar(idempotencyKey, request);
        return ResponseEntity.status(r.httpStatus()).body(r.corpo());
    }
}
