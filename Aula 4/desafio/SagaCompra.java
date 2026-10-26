import java.util.*;
import java.util.function.Consumer;

/**
 * Simulação da Saga de compra da Aula 4, sem bibliotecas: rode com `java SagaCompra.java` (Java 21).
 *
 * Três serviços com "bancos" separados (mapas em memória). Cada serviço grava o seu estado e o
 * evento na tabela outbox no MESMO passo (o que seria uma transação local). Um relay publica a
 * outbox no "broker" (uma fila), pelo menos uma vez: pode repetir mensagem. Por isso todo
 * consumidor guarda os ids de mensagem já processados (consumidor idempotente).
 *
 * O orquestrador mora no Checkout e só conhece a sequência e a compensação. Quem decide se há
 * estoque é o Estoque; quem decide cobrar e estornar é o Pagamento.
 */
public class SagaCompra {

    // ---------- infraestrutura: mensagem, outbox, broker ----------
    record Msg(String id, String tipo, String pedidoId, String origem) {}

    static final Deque<Msg> broker = new ArrayDeque<>();
    static final List<String> log = new ArrayList<>();

    /** Banco de um serviço: estado + outbox + mensagens já processadas. */
    static class Banco {
        final String dono;
        final List<Msg> outbox = new ArrayList<>();
        final Set<String> processadas = new HashSet<>();
        Banco(String dono) { this.dono = dono; }
        /** "Transação local": a mudança de estado e o evento entram juntos. */
        void transacao(Runnable mudanca, String tipoEvento, String pedidoId) {
            mudanca.run();
            outbox.add(new Msg(UUID.randomUUID().toString(), tipoEvento, pedidoId, dono));
        }
    }

    /** Relay (o papel do Debezium): lê a outbox e publica. Se cair antes de marcar, publica de novo. */
    static void relay(Banco b, boolean caiAntesDeMarcar) {
        for (Msg m : List.copyOf(b.outbox)) {
            broker.add(m);
            if (caiAntesDeMarcar) { log.add("  relay de " + b.dono + " caiu depois de publicar e antes de marcar"); return; }
            b.outbox.remove(m);
        }
    }

    // ---------- serviços ----------
    static final Banco bdCheckout = new Banco("checkout"), bdEstoque = new Banco("estoque"),
            bdPagamento = new Banco("pagamento");

    static final Map<String, String> pedidos = new HashMap<>();      // pedidoId -> status
    static final Map<String, Integer> estoque = new HashMap<>();     // sku -> disponível
    static final Map<String, String> reservas = new HashMap<>();     // pedidoId -> ATIVA/CONFIRMADA/LIBERADA
    static final Map<String, String> pagamentos = new HashMap<>();   // pedidoId -> APROVADO/ESTORNADO
    static final Map<String, Integer> estornosNoPsp = new HashMap<>(); // quantas devoluções o PSP fez
    static final Map<String, String> meusPedidos = new HashMap<>();  // modelo de leitura (CQRS)

    static int falhasDoEstornoRestantes = 0;     // simula PSP instável
    static boolean reservaExpiraAntesDeConfirmar = false;
    static final int MAX_TENTATIVAS_ESTORNO = 5;
    static final Map<String, Integer> tentativasEstorno = new HashMap<>();

    static void mudaPedido(String id, String novo) {
        log.add("  pedido " + id + ": " + pedidos.get(id) + " -> " + novo);
        pedidos.put(id, novo);
    }

    /** Orquestrador (no Checkout): recebe respostas e decide o próximo comando. */
    static void orquestrador(Msg m) {
        String p = m.pedidoId();
        switch (m.tipo()) {
            case "EstoqueReservado" -> bdCheckout.transacao(() -> mudaPedido(p, "AGUARDANDO_PAGAMENTO"), "CobrarPagamento", p);
            case "EstoqueIndisponivel" -> bdCheckout.transacao(() -> mudaPedido(p, "CANCELADO_SEM_COBRANCA"), "PedidoCanceladoSemCobranca", p);
            case "PagamentoAprovado" -> bdCheckout.transacao(() -> mudaPedido(p, "PAGO"), "ConfirmarReserva", p);
            case "ReservaConfirmada" -> bdCheckout.transacao(() -> mudaPedido(p, "CONCLUIDO"), "PedidoConcluido", p);
            case "ReservaRejeitada" -> bdCheckout.transacao(() -> mudaPedido(p, "ESTORNANDO"), "EstornarPagamento", p);
            case "EstornoFalhou" -> {
                int t = tentativasEstorno.merge(p, 1, Integer::sum);
                if (t < MAX_TENTATIVAS_ESTORNO) {
                    log.add("  estorno falhou (tentativa " + t + "), tenta de novo com espera crescente");
                    bdCheckout.transacao(() -> {}, "EstornarPagamento", p);
                } else {
                    bdCheckout.transacao(() -> mudaPedido(p, "ESTORNO_EM_REVISAO"), "AlertaEstorno", p);
                }
            }
            case "PagamentoEstornado" -> bdCheckout.transacao(() -> mudaPedido(p, "CANCELADO_COM_ESTORNO"), "PedidoCanceladoComEstorno", p);
            default -> {}
        }
    }

    static void servicoEstoque(Msg m) {
        String p = m.pedidoId();
        switch (m.tipo()) {
            case "PedidoCriado" -> {
                if (estoque.get("TV") > 0) bdEstoque.transacao(() -> { estoque.merge("TV", -1, Integer::sum); reservas.put(p, "ATIVA"); }, "EstoqueReservado", p);
                else bdEstoque.transacao(() -> {}, "EstoqueIndisponivel", p);
            }
            case "ConfirmarReserva" -> {
                if (reservaExpiraAntesDeConfirmar && "ATIVA".equals(reservas.get(p))) {
                    // o job de expiração liberou a unidade e outra pessoa levou
                    reservas.put(p, "LIBERADA");
                    log.add("  reserva expirou e a última unidade foi vendida para outra pessoa");
                }
                if ("ATIVA".equals(reservas.get(p))) bdEstoque.transacao(() -> reservas.put(p, "CONFIRMADA"), "ReservaConfirmada", p);
                else bdEstoque.transacao(() -> {}, "ReservaRejeitada", p);
            }
            default -> {}
        }
    }

    static void servicoPagamento(Msg m) {
        String p = m.pedidoId();
        switch (m.tipo()) {
            case "CobrarPagamento" -> bdPagamento.transacao(() -> pagamentos.put(p, "APROVADO"), "PagamentoAprovado", p);
            case "EstornarPagamento" -> {
                if ("ESTORNADO".equals(pagamentos.get(p))) { // já devolvido: só confirma de novo
                    bdPagamento.transacao(() -> {}, "PagamentoEstornado", p);
                } else if (falhasDoEstornoRestantes > 0) {
                    falhasDoEstornoRestantes--;
                    bdPagamento.transacao(() -> {}, "EstornoFalhou", p);
                } else {
                    // chave de idempotência no PSP = id do pagamento: ele devolve uma vez só
                    estornosNoPsp.merge(p, 1, Integer::sum);
                    bdPagamento.transacao(() -> pagamentos.put(p, "ESTORNADO"), "PagamentoEstornado", p);
                }
            }
            default -> {}
        }
    }

    /** Projeção do CQRS: atualiza "Meus Pedidos" a partir dos eventos, sem tocar nos bancos de escrita. */
    static void projecao(Msg m) {
        Map<String, String> visivel = Map.of("PedidoCriado", "Processando", "PagamentoAprovado", "Pago, confirmando estoque",
                "EstornarPagamento", "Produto esgotou, devolvendo seu Pix", "PedidoConcluido", "Confirmado",
                "PedidoCanceladoSemCobranca", "Cancelado, nada foi cobrado",
                "PedidoCanceladoComEstorno", "Cancelado, Pix devolvido", "AlertaEstorno", "Devolução em análise");
        if (visivel.containsKey(m.tipo())) meusPedidos.put(m.pedidoId(), visivel.get(m.tipo()));
    }

    /** Entrega uma mensagem a todos os inscritos, cada um com a sua lista de já processadas. */
    static final Map<String, Consumer<Msg>> inscritos = new LinkedHashMap<>();
    static final Map<String, Set<String>> processadasPor = new HashMap<>();
    static int duplicadasIgnoradas = 0;

    static void entregar(Msg m) {
        for (var e : inscritos.entrySet()) {
            Set<String> vistas = processadasPor.computeIfAbsent(e.getKey(), k -> new HashSet<>());
            if (!vistas.add(m.id())) { duplicadasIgnoradas++; continue; }  // consumidor idempotente
            e.getValue().accept(m);
        }
    }

    static void rodar(boolean relayCaiUmaVez) {
        boolean caiu = !relayCaiUmaVez;
        while (true) {
            relay(bdCheckout, !caiu && !bdCheckout.outbox.isEmpty() && (caiu = true));
            relay(bdEstoque, false);
            relay(bdPagamento, false);
            if (broker.isEmpty()) return;
            entregar(broker.poll());
        }
    }

    static void reset(int estoqueTv, int falhasEstorno, boolean expira) {
        broker.clear(); log.clear(); pedidos.clear(); reservas.clear(); pagamentos.clear(); estornosNoPsp.clear();
        meusPedidos.clear(); processadasPor.clear(); tentativasEstorno.clear(); duplicadasIgnoradas = 0;
        for (Banco b : List.of(bdCheckout, bdEstoque, bdPagamento)) b.outbox.clear();
        estoque.put("TV", estoqueTv); falhasDoEstornoRestantes = falhasEstorno; reservaExpiraAntesDeConfirmar = expira;
    }

    static int falhas = 0;
    static void confere(String oQue, Object obtido, Object esperado) {
        boolean ok = Objects.equals(obtido, esperado);
        if (!ok) falhas++;
        System.out.printf("  [%s] %s: %s%n", ok ? "OK" : "FALHOU", oQue, obtido + (ok ? "" : " (esperado " + esperado + ")"));
    }

    static String cenario(String titulo, int estoqueTv, int falhasEstorno, boolean expira, boolean relayCai) {
        reset(estoqueTv, falhasEstorno, expira);
        String p = "pedido-1";
        System.out.println("\n== " + titulo);
        bdCheckout.transacao(() -> pedidos.put(p, "PENDENTE"), "PedidoCriado", p);
        rodar(relayCai);
        log.forEach(System.out::println);
        System.out.println("  Meus Pedidos mostra: " + meusPedidos.get(p));
        return p;
    }

    public static void main(String[] args) {
        inscritos.put("estoque", SagaCompra::servicoEstoque);
        inscritos.put("pagamento", SagaCompra::servicoPagamento);
        inscritos.put("orquestrador", SagaCompra::orquestrador);
        inscritos.put("projecao", SagaCompra::projecao);

        String p = cenario("1. Caminho feliz", 1, 0, false, false);
        confere("status final", pedidos.get(p), "CONCLUIDO");
        confere("reserva", reservas.get(p), "CONFIRMADA");

        p = cenario("2. O incidente: Pix aprovado, estoque rejeita a confirmação", 1, 0, true, false);
        confere("status final", pedidos.get(p), "CANCELADO_COM_ESTORNO");
        confere("nao e cancelamento sem cobranca", "CANCELADO_SEM_COBRANCA".equals(pedidos.get(p)), false);
        confere("pagamento", pagamentos.get(p), "ESTORNADO");
        confere("devoluções feitas no PSP", estornosNoPsp.get(p), 1);

        p = cenario("3. O estorno falha 2 vezes (PSP instável) e passa na 3ª", 1, 2, true, false);
        confere("status final", pedidos.get(p), "CANCELADO_COM_ESTORNO");
        confere("nao e cancelamento sem cobranca", "CANCELADO_SEM_COBRANCA".equals(pedidos.get(p)), false);
        confere("devoluções feitas no PSP", estornosNoPsp.get(p), 1);

        p = cenario("4. O estorno falha sempre: para em revisão, nunca cancela sem devolver", 1, 99, true, false);
        confere("status final", pedidos.get(p), "ESTORNO_EM_REVISAO");
        confere("pagamento ainda não devolvido", pagamentos.get(p), "APROVADO");
        confere("nao fechou com estorno", "CANCELADO_COM_ESTORNO".equals(pedidos.get(p)), false);
        confere("nao fechou sem cobranca", "CANCELADO_SEM_COBRANCA".equals(pedidos.get(p)), false);

        p = cenario("5. Relay cai depois de publicar (mensagem duplicada no broker)", 1, 0, true, true);
        confere("status final", pedidos.get(p), "CANCELADO_COM_ESTORNO");
        confere("nao e cancelamento sem cobranca", "CANCELADO_SEM_COBRANCA".equals(pedidos.get(p)), false);
        confere("devoluções feitas no PSP", estornosNoPsp.get(p), 1);
        confere("mensagens duplicadas ignoradas > 0", duplicadasIgnoradas > 0, true);

        p = cenario("6. Sem estoque desde o início: nada é cobrado", 0, 0, false, false);
        confere("status final", pedidos.get(p), "CANCELADO_SEM_COBRANCA");
        confere("nao e cancelamento com estorno", "CANCELADO_COM_ESTORNO".equals(pedidos.get(p)), false);
        confere("pagamento", pagamentos.get(p), null);

        System.out.println(falhas == 0 ? "\nTODOS OS CENÁRIOS PASSARAM" : "\n" + falhas + " VERIFICAÇÃO(ÕES) FALHARAM");
        if (falhas > 0) System.exit(1);
    }
}
