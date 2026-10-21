package br.com.ada.obs;

/**
 * A particao do Kafka so garante ordem dentro dela. A mesma chave cai sempre na mesma particao.
 * A chave tem de ser o orderId: e a entidade cujas transicoes precisam chegar em ordem.
 */
public final class Particao {

    private Particao() {}

    public static int de(String chave, int particoes) {
        if (particoes <= 0) throw new IllegalArgumentException("particoes");
        int h = chave.hashCode() & 0x7fffffff;
        return h % particoes;
    }
}
