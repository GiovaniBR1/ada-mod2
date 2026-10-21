package br.com.ada.obs;

/** USE de um recurso: utilizacao (em uso / tamanho), saturacao (fila) e erros. */
public final class PoolConexoes {

    private final String nome;
    private final int tamanho;
    private int emUso;
    private int fila;
    private int erros;

    public PoolConexoes(String nome, int tamanho) {
        this.nome = nome;
        this.tamanho = tamanho;
    }

    public PoolConexoes ocupado(int emUso, int fila, int erros) {
        this.emUso = emUso;
        this.fila = fila;
        this.erros = erros;
        return this;
    }

    public String nome() { return nome; }
    public int tamanho() { return tamanho; }
    public int emUso() { return emUso; }
    public int fila() { return fila; }
    public int erros() { return erros; }

    public double utilizacao() { return tamanho == 0 ? 0 : (double) emUso / tamanho; }
}
