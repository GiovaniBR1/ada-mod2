package br.com.ada.obs;

public final class PainelAlertas {

    private PainelAlertas() {}

    public static boolean mediaAcimaDe(double mediaSegundos, double limiteSegundos) {
        return mediaSegundos > limiteSegundos;
    }

    public static boolean quantilAcimaDe(double quantilSegundos, double limiteSegundos) {
        return quantilSegundos > limiteSegundos;
    }

    /** Little: pedidos em andamento acima do dobro da linha de base. */
    public static boolean emAndamentoAlto(int atual, int baseline) {
        return atual > 2L * baseline;
    }

    /**
     * Fila no pool com CPU abaixo de 40% e zero HTTP 500.
     * E o caso em que o painel da CPU e o painel de 500 continuam verdes.
     */
    public static boolean poolSaturadoComPainelVerde(PoolConexoes pool, double cpu, int http500) {
        return pool.fila() > 0 && pool.utilizacao() >= 1.0 && cpu < 0.40 && http500 == 0;
    }
}
