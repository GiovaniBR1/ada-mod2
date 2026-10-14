package br.com.ada.pagamentos;

import br.com.ada.pagamentos.Dtos.CobrancaParceiroRequest;
import br.com.ada.pagamentos.Dtos.PagamentoRequest;
import br.com.ada.pagamentos.Dtos.PagamentoResponse;
import br.com.ada.pagamentos.Excecoes.ChaveReutilizadaException;
import br.com.ada.pagamentos.Excecoes.EmProcessamentoException;
import br.com.ada.pagamentos.Excecoes.ParceiroErroHttpException;
import br.com.ada.pagamentos.Excecoes.ParceiroTimeoutException;
import org.springframework.stereotype.Service;

/**
 * Regra do checkout:
 * - parceiro respondeu OK -> 201 com o status dele;
 * - ambíguo (read timeout ou 5xx que não seja 503) -> fallback: PENDENTE + 202 (reconciliação fecha depois);
 * - sabemos que não cobrou (circuito aberto, bulkhead cheio, conexão recusada, 429/503) -> libera a chave
 *   e propaga a exceção; o @RestControllerAdvice responde 503 + Retry-After.
 * Falha nunca vira "APROVADO".
 */
@Service
public class PagamentoService {

    public record Resultado(int httpStatus, PagamentoResponse corpo) {}

    private final ParceiroClient parceiro;
    private final IdempotenciaStore idempotencia;
    private final ContingenciaStore contingencia;

    public PagamentoService(ParceiroClient parceiro, IdempotenciaStore idempotencia, ContingenciaStore contingencia) {
        this.parceiro = parceiro;
        this.idempotencia = idempotencia;
        this.contingencia = contingencia;
    }

    public Resultado pagar(String chave, PagamentoRequest req) {
        String hash = req.id() + "|" + req.valor().stripTrailingZeros().toPlainString();
        var existente = idempotencia.reservar(chave, hash);
        if (existente != null) {
            if (!existente.hashCorpo().equals(hash)) throw new ChaveReutilizadaException();
            if (!existente.concluido()) throw new EmProcessamentoException();
            return new Resultado(existente.httpStatus(), existente.resposta()); // resposta guardada
        }

        try {
            var resp = parceiro.cobrar(new CobrancaParceiroRequest(chave, req.id(), req.valor()));
            var corpo = new PagamentoResponse(req.id(), resp.status());
            idempotencia.concluir(chave, hash, 201, corpo);
            return new Resultado(201, corpo);
        } catch (ParceiroTimeoutException e) {
            return pendente(chave, hash, req);
        } catch (ParceiroErroHttpException e) {
            if (!e.naoProcessou()) return pendente(chave, hash, req);
            idempotencia.liberar(chave);
            throw e;
        } catch (RuntimeException e) {
            // CallNotPermittedException, BulkheadFullException, ParceiroConexaoException: nada foi cobrado.
            idempotencia.liberar(chave);
            throw e;
        }
    }

    private Resultado pendente(String chave, String hash, PagamentoRequest req) {
        try {
            contingencia.registrarPendente(chave, req.id());
        } catch (RuntimeException e) {
            // Não conseguimos registrar: deixa a chave "em processamento" (não libera: o parceiro pode ter cobrado).
            throw e;
        }
        var corpo = new PagamentoResponse(req.id(), "PENDENTE");
        idempotencia.concluir(chave, hash, 202, corpo);
        return new Resultado(202, corpo);
    }
}
