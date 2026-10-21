package br.com.ada.obs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Histograma no formato que o Prometheus soma entre replicas.
 * As amostras individuais so existem aqui para o teste mostrar o percentil exato.
 * Em producao o Micrometer guarda os baldes, nao cada pedido.
 */
public final class Histograma {

    public static final double[] LIMITES = {0.05, 0.1, 0.25, 0.5, 1, 2, 4, 8, Double.POSITIVE_INFINITY};
    private static final Set<String> PROIBIDOS = Set.of(
            "order_id", "orderid", "user_id", "userid", "cpf", "email", "trace_id", "sku_id", "nome");
    private static final int MAX_AMOSTRAS = 20_000;

    private static final class Serie {
        final long[] baldes = new long[LIMITES.length];
        long count;
        double sum;
        final List<Double> amostras = new ArrayList<>();
    }

    private final Map<String, Serie> series = new TreeMap<>();

    public void observar(double segundos, Map<String, String> labels) {
        if (segundos < 0) throw new IllegalArgumentException("duracao negativa");
        for (var e : labels.entrySet()) {
            String chave = e.getKey().toLowerCase(Locale.ROOT);
            if (PROIBIDOS.contains(chave)) {
                throw new IllegalArgumentException("label proibido: " + e.getKey());
            }
            String valor = e.getValue();
            if (valor != null && (valor.matches("\\d{11}") || valor.contains("@"))) {
                throw new IllegalArgumentException("valor de label parece dado pessoal: " + e.getKey());
            }
        }
        Serie serie = series.computeIfAbsent(chave(labels), k -> new Serie());
        serie.count++;
        serie.sum += segundos;
        if (serie.amostras.size() < MAX_AMOSTRAS) {
            serie.amostras.add(segundos);
        }
        for (int i = 0; i < LIMITES.length; i++) {
            if (segundos <= LIMITES[i]) {
                serie.baldes[i]++;
                return;
            }
        }
    }

    public int series() { return series.size(); }

    public long count() {
        return series.values().stream().mapToLong(s -> s.count).sum();
    }

    public double media() {
        long n = count();
        if (n == 0) throw new IllegalStateException("sem amostras");
        double soma = series.values().stream().mapToDouble(s -> s.sum).sum();
        return soma / n;
    }

    /** Menor valor v tal que pelo menos p das amostras guardadas sao menores ou iguais a v. */
    public double percentil(double p) {
        List<Double> todas = new ArrayList<>();
        series.values().forEach(s -> todas.addAll(s.amostras));
        if (todas.isEmpty()) throw new IllegalStateException("sem amostras");
        todas.sort(Double::compare);
        int indice = (int) Math.ceil(p * todas.size()) - 1;
        if (indice < 0) indice = 0;
        if (indice >= todas.size()) indice = todas.size() - 1;
        return todas.get(indice);
    }

    /**
     * Quantil a partir dos baldes, no estilo do histogram_quantile:
     * achei o balde onde o acumulado passa de p * total e interpolo entre o limite anterior e o deste balde.
     */
    public double quantilPorBalde(double p) {
        long total = count();
        if (total == 0) throw new IllegalStateException("sem amostras");
        long[] acumuladoPorBalde = new long[LIMITES.length];
        for (Serie serie : series.values()) {
            for (int i = 0; i < LIMITES.length; i++) {
                acumuladoPorBalde[i] += serie.baldes[i];
            }
        }
        double rank = p * total;
        long acumulado = 0;
        double limiteAnterior = 0;
        for (int i = 0; i < LIMITES.length; i++) {
            long neste = acumuladoPorBalde[i];
            long proximo = acumulado + neste;
            if (proximo >= rank && neste > 0) {
                double limite = LIMITES[i];
                if (Double.isInfinite(limite)) return Double.POSITIVE_INFINITY;
                return limiteAnterior + (limite - limiteAnterior) * (rank - acumulado) / neste;
            }
            acumulado = proximo;
            if (!Double.isInfinite(LIMITES[i])) {
                limiteAnterior = LIMITES[i];
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    public String prometheus(String nome) {
        StringBuilder sb = new StringBuilder();
        sb.append("# TYPE ").append(nome).append(" histogram\n");
        for (var e : series.entrySet()) {
            long acumulado = 0;
            Serie serie = e.getValue();
            for (int i = 0; i < LIMITES.length; i++) {
                acumulado += serie.baldes[i];
                String le = Double.isInfinite(LIMITES[i]) ? "+Inf" : Double.toString(LIMITES[i]);
                sb.append(nome).append("_bucket{").append(e.getKey()).append(",le=\"").append(le).append("\"} ")
                        .append(acumulado).append('\n');
            }
            sb.append(nome).append("_sum{").append(e.getKey()).append("} ").append(serie.sum).append('\n');
            sb.append(nome).append("_count{").append(e.getKey()).append("} ").append(serie.count).append('\n');
        }
        return sb.toString();
    }

    private static String chave(Map<String, String> labels) {
        return labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=\"" + e.getValue() + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}
