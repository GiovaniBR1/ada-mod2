package br.com.ada.pagamentos;

import br.com.ada.pagamentos.Dtos.CobrancaParceiroRequest;
import br.com.ada.pagamentos.Dtos.CobrancaParceiroResponse;
import br.com.ada.pagamentos.Dtos.PagamentoRequest;
import br.com.ada.pagamentos.Dtos.PagamentoResponse;
import br.com.ada.pagamentos.Excecoes.ChaveReutilizadaException;
import br.com.ada.pagamentos.Excecoes.EmProcessamentoException;
import br.com.ada.pagamentos.Excecoes.ParceiroConexaoException;
import br.com.ada.pagamentos.Excecoes.ParceiroErroHttpException;
import br.com.ada.pagamentos.Excecoes.ParceiroTimeoutException;
import org.springframework.stereotype.Service;

/**
 * Regra do checkout:
 * - parceiro respondeu OK -> 201 com o status dele;
 * - recusa 402 -> 402 gravado; a chamada saiu e não abre caminho de retentativa;
 * - ambíguo (read timeout ou 5xx que não seja 503) -> uma chamada, PENDENTE + 202;
 * - 503 ou 429 do parceiro -> no máximo 3 chamadas, contando a primeira; se as três
 *   voltarem assim, grava PENDENTE e a mesma chave fica para a reconciliação;
 * - chamada que não saiu (circuito aberto, bulkhead cheio, conexão recusada nas 3 tentativas)
 *   -> libera a chave e propaga; o advice responde 503 + Retry-After.
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

    /** Igual a resilience4j.retry.instances.parceiro.max-attempts: inclui a primeira chamada. */
    static final int MAX_CHAMADAS = 3;

    public Resultado pagar(String chave, PagamentoRequest req) {
        String hash = req.id() + "|" + req.valor().stripTrailingZeros().toPlainString();
        var existente = idempotencia.reservar(chave, hash);
        if (existente != null) {
            if (!existente.hashCorpo().equals(hash)) throw new ChaveReutilizadaException();
            if (!existente.concluido()) throw new EmProcessamentoException();
            return new Resultado(existente.httpStatus(), existente.resposta()); // resposta guardada
        }

        try {
            var resp = cobrarComRetentativa(chave, req);
            if ("RECUSADO".equals(resp.status())) {
                var corpo = new PagamentoResponse(req.id(), "RECUSADO");
                idempotencia.concluir(chave, hash, 402, corpo);
                return new Resultado(402, corpo);
            }
            var corpo = new PagamentoResponse(req.id(), resp.status());
            idempotencia.concluir(chave, hash, 201, corpo);
            return new Resultado(201, corpo);
        } catch (ParceiroTimeoutException | ParceiroErroHttpException e) {
            return pendente(chave, hash, req);
        } catch (RuntimeException e) {
            // CallNotPermittedException, BulkheadFullException, ParceiroConexaoException: nada foi cobrado.
            idempotencia.liberar(chave);
            throw e;
        }
    }

    private CobrancaParceiroResponse cobrarComRetentativa(String chave, PagamentoRequest req) {
        ParceiroErroHttpException ultimaHttp = null;
        for (int chamada = 1; chamada <= MAX_CHAMADAS; chamada++) {
            try {
                return parceiro.cobrar(new CobrancaParceiroRequest(chave, req.id(), req.valor()));
            } catch (ParceiroTimeoutException e) {
                throw e;
            } catch (ParceiroErroHttpException e) {
                if (!e.naoProcessou()) throw e;
                ultimaHttp = e;
            } catch (ParceiroConexaoException e) {
                if (chamada == MAX_CHAMADAS) throw e;
            }
        }
        throw ultimaHttp;
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
