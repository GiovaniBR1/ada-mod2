package br.com.ada.pagamentos;

import br.com.ada.pagamentos.Dtos.PagamentoResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Guarda as Idempotency-Keys. Em memória para o desafio; em produção seria a tabela
 * idempotency_keys com UNIQUE na chave. putIfAbsent faz o papel do INSERT com UNIQUE:
 * operação atômica, só UM chamador vence.
 */
@Component
public class IdempotenciaStore {

    public record Registro(String hashCorpo, boolean concluido, int httpStatus, PagamentoResponse resposta) {}

    private final Map<String, Registro> chaves = new ConcurrentHashMap<>();

    /** Tenta reservar a chave. Devolve null se reservou; senão devolve o registro existente. */
    public Registro reservar(String chave, String hashCorpo) {
        return chaves.putIfAbsent(chave, new Registro(hashCorpo, false, 0, null));
    }

    public void concluir(String chave, String hashCorpo, int httpStatus, PagamentoResponse resposta) {
        chaves.put(chave, new Registro(hashCorpo, true, httpStatus, resposta));
    }

    /** Libera a chave quando SABEMOS que nada foi cobrado (cliente pode tentar de novo). */
    public void liberar(String chave) { chaves.remove(chave); }

    public void limpar() { chaves.clear(); }
}
