package br.com.ada.obs;

/**
 * Head: a decisao sai do trace-id, antes de saber se a requisicao travou.
 * Tail: a decisao sai depois, com erro e duracao ja conhecidos.
 * Os dois olham os ultimos 8 bytes do trace-id (16 hex), que e a parte que varia de um trace para o outro.
 */
public final class Amostrador {

    public boolean head(String traceId, double taxa) {
        long h = Long.parseUnsignedLong(traceId.substring(16), 16);
        int corte = (int) Math.round(taxa * 10_000);
        return Math.floorMod(h, 10_000) < corte;
    }

    /** Guarda erro, guarda duracao acima do limiar, e ainda guarda uma fracao do resto para a amostra continuar representativa. */
    public boolean tail(String traceId, boolean erro, double duracaoSegundos, double limiarSegundos, double taxaDoResto) {
        if (erro || duracaoSegundos > limiarSegundos) {
            return true;
        }
        return head(traceId, taxaDoResto);
    }
}
