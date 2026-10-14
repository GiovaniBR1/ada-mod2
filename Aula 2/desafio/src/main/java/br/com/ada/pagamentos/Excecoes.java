package br.com.ada.pagamentos;

/** Exceções de integração. Cada uma diz se o parceiro PODE ter cobrado (ambígua) ou não. */
final class Excecoes {
    private Excecoes() {}

    /** Não conseguimos nem conectar: sabemos que o parceiro NÃO cobrou. */
    static class ParceiroConexaoException extends RuntimeException {
        ParceiroConexaoException(Throwable causa) { super("Falha ao conectar no parceiro", causa); }
    }

    /** Enviamos e a resposta não veio a tempo: o parceiro PODE ter cobrado (ambíguo). */
    static class ParceiroTimeoutException extends RuntimeException {
        ParceiroTimeoutException(Throwable causa) { super("Parceiro não respondeu a tempo", causa); }
    }

    /** O parceiro respondeu com erro HTTP. 429 e 503 = "não processei, tente depois"; outros 5xx = ambíguo. */
    static class ParceiroErroHttpException extends RuntimeException {
        final int status;
        ParceiroErroHttpException(int status) { super("Parceiro respondeu HTTP " + status); this.status = status; }
        boolean naoProcessou() { return status == 429 || status == 503; }
    }

    /** A contingência (registrar PENDENTE) também falhou. */
    static class ContingenciaIndisponivelException extends RuntimeException {
        ContingenciaIndisponivelException(String msg) { super(msg); }
    }

    /** Mesma Idempotency-Key ainda em processamento. */
    static class EmProcessamentoException extends RuntimeException {
        EmProcessamentoException() { super("Requisição com esta Idempotency-Key ainda em processamento"); }
    }

    /** Mesma Idempotency-Key com corpo diferente. */
    static class ChaveReutilizadaException extends RuntimeException {
        ChaveReutilizadaException() { super("Idempotency-Key já usada com outro corpo"); }
    }
}
