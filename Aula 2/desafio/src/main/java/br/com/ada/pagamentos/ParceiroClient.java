package br.com.ada.pagamentos;

import br.com.ada.pagamentos.Dtos.CobrancaParceiroRequest;
import br.com.ada.pagamentos.Dtos.CobrancaParceiroResponse;
import br.com.ada.pagamentos.Excecoes.ParceiroConexaoException;
import br.com.ada.pagamentos.Excecoes.ParceiroErroHttpException;
import br.com.ada.pagamentos.Excecoes.ParceiroTimeoutException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente HTTP do parceiro, com RestClient (Spring 6.1+), parecido com requests.Session do Python.
 * Proteção contra lentidão: connect/read timeout NATIVOS do cliente (não TimeLimiter: ele não
 * interrompe uma chamada bloqueante; quem libera a thread de verdade é o timeout do socket).
 */
@Component
public class ParceiroClient {

    private final RestClient restClient;

    public ParceiroClient(RestClient.Builder builder, ParceiroProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        this.restClient = builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    /** Ordem: CircuitBreaker( Bulkhead( chamada ) ). Toda exceção lançada aqui conta como falha no circuito. */
    @CircuitBreaker(name = "parceiro")
    @Bulkhead(name = "parceiro")
    public CobrancaParceiroResponse cobrar(CobrancaParceiroRequest req) {
        try {
            return restClient.post()
                    .uri("/cobrancas")
                    .header("Idempotency-Key", req.idempotencyKey())
                    .body(req.corpo())
                    .retrieve()
                    .body(CobrancaParceiroResponse.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 402) {
                return new CobrancaParceiroResponse(req.id(), "RECUSADO");
            }
            throw new ParceiroErroHttpException(status);
        } catch (RestClientException e) {
            // ResourceAccessException (I/O) ou erro ao ler o corpo: olhamos a causa raiz.
            // Achado real nos testes: o read timeout pode estourar já LENDO o corpo e vir embrulhado
            // como RestClientException genérica, não como ResourceAccessException.
            Throwable raiz = NestedExceptionUtils.getMostSpecificCause(e);
            String msg = raiz.getMessage() == null ? "" : raiz.getMessage().toLowerCase();
            boolean naoConectou = raiz instanceof ConnectException
                    || (raiz instanceof SocketTimeoutException && msg.contains("connect"));
            if (naoConectou) {
                throw new ParceiroConexaoException(e); // parceiro não recebeu nada: seguro dizer "não cobrado"
            }
            throw new ParceiroTimeoutException(e); // já enviamos: ambíguo (read timeout, conexão caída no meio)
        }
    }
}
