package br.com.ada.pagamentos;

import br.com.ada.pagamentos.Excecoes.ContingenciaIndisponivelException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Fallback: registra o pagamento como PENDENTE para a reconciliação consultar o parceiro depois.
 * Em memória aqui; em produção, tabela de pagamentos ou fila. O flag "disponivel" simula a queda
 * da contingência nos testes.
 */
@Component
public class ContingenciaStore {

    private final Map<String, String> pendentes = new ConcurrentHashMap<>();
    private volatile boolean disponivel = true;

    public void registrarPendente(String idempotencyKey, String pagamentoId) {
        if (!disponivel) {
            throw new ContingenciaIndisponivelException("Não foi possível registrar o pagamento como PENDENTE");
        }
        pendentes.put(idempotencyKey, pagamentoId);
    }

    public int totalPendentes() { return pendentes.size(); }
    public void setDisponivel(boolean disponivel) { this.disponivel = disponivel; }
    public void limpar() { pendentes.clear(); disponivel = true; }
}
