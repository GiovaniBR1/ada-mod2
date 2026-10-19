package br.com.ada.checkout;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PedidoController {

    private final PedidoRepository pedidos;

    public PedidoController(PedidoRepository pedidos) { this.pedidos = pedidos; }

    @GetMapping("/pedidos/{id}")
    public ResponseEntity<Map<String, String>> estado(@PathVariable long id) {
        return pedidos.estado(id)
                .map(e -> ResponseEntity.ok(Map.of("id", String.valueOf(id), "estadoSaga", e.name())))
                .orElse(ResponseEntity.notFound().build());
    }
}
