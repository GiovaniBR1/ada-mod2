package br.com.ada.pagamentos;

import br.com.ada.pagamentos.Excecoes.ChaveReutilizadaException;
import br.com.ada.pagamentos.Excecoes.ContingenciaIndisponivelException;
import br.com.ada.pagamentos.Excecoes.EmProcessamentoException;
import br.com.ada.pagamentos.Excecoes.ParceiroConexaoException;
import br.com.ada.pagamentos.Excecoes.ParceiroErroHttpException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Transforma exceções em respostas RFC 7807 (Problem Details), sem stack trace.
 * Erros de entrada (sem Idempotency-Key, JSON inválido, validação) já viram 400 Problem Details
 * pela classe-mãe ResponseEntityExceptionHandler.
 * Parecido com um @app.exception_handler do FastAPI.
 */
@RestControllerAdvice
public class TratadorGlobalDeErros extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TratadorGlobalDeErros.class);
    private final ParceiroProperties props;

    public TratadorGlobalDeErros(ParceiroProperties props) { this.props = props; }

    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<ProblemDetail> circuitoAberto(CallNotPermittedException e) {
        return indisponivel("Circuito aberto: o parceiro de pagamentos está instável. Nada foi cobrado.", "circuito-aberto");
    }

    @ExceptionHandler(BulkheadFullException.class)
    ResponseEntity<ProblemDetail> bulkheadCheio(BulkheadFullException e) {
        return indisponivel("Limite de chamadas simultâneas ao parceiro atingido. Nada foi cobrado.", "bulkhead-cheio");
    }

    @ExceptionHandler(ParceiroConexaoException.class)
    ResponseEntity<ProblemDetail> semConexao(ParceiroConexaoException e) {
        log.warn("Falha de conexão com o parceiro: {}", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        return indisponivel("Não foi possível conectar ao parceiro. Nada foi cobrado.", "parceiro-sem-conexao");
    }

    @ExceptionHandler(ParceiroErroHttpException.class)
    ResponseEntity<ProblemDetail> parceiroRecusouCarga(ParceiroErroHttpException e) {
        return indisponivel("O parceiro respondeu " + e.status + " (não processou). Nada foi cobrado.", "parceiro-sobrecarregado");
    }

    @ExceptionHandler(ContingenciaIndisponivelException.class)
    ResponseEntity<ProblemDetail> contingenciaFalhou(ContingenciaIndisponivelException e) {
        log.error("Contingência falhou: {}", e.getMessage());
        var pd = problema(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível confirmar o pagamento nem registrá-lo como pendente. Não reenvie com outra chave; consulte o status mais tarde.", "contingencia-indisponivel");
        return ResponseEntity.status(500).body(pd);
    }

    @ExceptionHandler(EmProcessamentoException.class)
    ResponseEntity<ProblemDetail> emProcessamento(EmProcessamentoException e) {
        return ResponseEntity.status(409).body(problema(HttpStatus.CONFLICT, e.getMessage(), "em-processamento"));
    }

    @ExceptionHandler(ChaveReutilizadaException.class)
    ResponseEntity<ProblemDetail> chaveReutilizada(ChaveReutilizadaException e) {
        return ResponseEntity.status(422).body(problema(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), "chave-reutilizada"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception e) {
        log.error("Erro inesperado", e); // stack trace só no log, nunca na resposta
        return ResponseEntity.status(500).body(problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "erro-interno"));
    }

    private ResponseEntity<ProblemDetail> indisponivel(String detalhe, String tipo) {
        return ResponseEntity.status(503)
                .header("Retry-After", String.valueOf(props.retryAfterSegundos()))
                .body(problema(HttpStatus.SERVICE_UNAVAILABLE, detalhe, tipo));
    }

    private static ProblemDetail problema(HttpStatus status, String detalhe, String tipo) {
        var pd = ProblemDetail.forStatusAndDetail(status, detalhe);
        pd.setType(java.net.URI.create("https://exemplo.ada/problemas/" + tipo));
        return pd;
    }
}
