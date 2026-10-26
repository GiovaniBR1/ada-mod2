package br.com.ada.obs;

public final class Pedido {
    private final String orderId;
    private final String clienteRef;
    private final String skuId;
    private final long centavos;
    private final String traceId;
    private final String traceparent;
    private EstadoPedido estado = EstadoPedido.PENDENTE;
    private boolean reservado;
    private boolean estoqueConfirmado;
    private boolean pagamentoCapturado;
    private boolean estornoSolicitado;
    private boolean estornoConfirmado;
    private String pagamentoId;
    private int consultasEstoque;
    private int consultasPagamento;
    private int consultasEstorno;
    private long entrouEmRevisaoMs;
    private int alertasFinanceiros;

    public Pedido(String orderId, String clienteRef, String skuId, long centavos, String traceId, String traceparent) {
        this.orderId = orderId;
        this.clienteRef = clienteRef;
        this.skuId = skuId;
        this.centavos = centavos;
        this.traceId = traceId;
        this.traceparent = traceparent;
    }

    public String orderId() { return orderId; }
    public String clienteRef() { return clienteRef; }
    public String skuId() { return skuId; }
    public long centavos() { return centavos; }
    public String traceId() { return traceId; }
    public String traceparent() { return traceparent; }
    public EstadoPedido estado() { return estado; }
    public boolean reservado() { return reservado; }
    public boolean pagamentoCapturado() { return pagamentoCapturado; }
    public boolean estornoConfirmado() { return estornoConfirmado; }
    public String pagamentoId() { return pagamentoId; }
    public int consultasEstoque() { return consultasEstoque; }
    public int consultasPagamento() { return consultasPagamento; }
    public int consultasEstorno() { return consultasEstorno; }
    public long entrouEmRevisaoMs() { return entrouEmRevisaoMs; }
    public int alertasFinanceiros() { return alertasFinanceiros; }

    void estado(EstadoPedido estado) { this.estado = estado; }
    void reservado(boolean v) { this.reservado = v; }
    void estoqueConfirmado(boolean v) { this.estoqueConfirmado = v; }
    void pagamentoCapturado(boolean v) { this.pagamentoCapturado = v; }
    void estornoSolicitado(boolean v) { this.estornoSolicitado = v; }
    boolean estornoSolicitado() { return estornoSolicitado; }
    void estornoConfirmado(boolean v) { this.estornoConfirmado = v; }
    void pagamentoId(String id) { this.pagamentoId = id; }
    void maisConsultaEstoque() { consultasEstoque++; }
    void maisConsultaPagamento() { consultasPagamento++; }
    void maisConsultaEstorno() { consultasEstorno++; }
    void entrouEmRevisaoMs(long ms) { if (entrouEmRevisaoMs == 0) entrouEmRevisaoMs = ms; }
    void alertaFinanceiro() { alertasFinanceiros++; }
}
