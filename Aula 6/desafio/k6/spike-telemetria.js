// Roteiro do spike da Aula 6. Este script so mede a carga: faz POST em
// /checkout/finalizar e nao injeta sono. A falha de 2% das chamadas de estoque
// dormindo 8 s e injetada pelos testes Java dos niveis 1 a 3
// (TelemetriaTest e SagaDiagnosticoTest). Nao faz parte do laboratorio opcional.
//
// O que os testes Java conferem quando a falha esta injetada:
// 1. O alerta de quantil ou de pedidos em andamento dispara.
// 2. O alerta de media acima de 300 ms fica em silencio.
// 3. Um trace lento tem o mesmo trace_id no checkout e no estoque.
// 4. A saida de metricas nao tem label order_id, user_id nem cpf.

import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 500,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      stages: [
        { duration: '1m', target: 500 },   // base
        { duration: '10s', target: 5000 }, // sobe reto, no formato de spike do k6
        { duration: '1m', target: 5000 },  // pico curto
        { duration: '10s', target: 500 },  // volta
        { duration: '1m', target: 500 },   // a fila tem de esvaziar aqui
      ],
    },
  },
  thresholds: {
    // A media pode continuar perto de 120 ms. Ela nao e o portao.
    // Este script nao injeta a falha de 8 s. O threshold p(99)<800 vale para a
    // carga sem essa falha. A falha de 2%/8 s fica nos testes Java.
    http_req_duration: ['p(99)<800'],
  },
};

export default function () {
  const id = `k6-${__VU}-${__ITER}`;
  const res = http.post(
    'http://localhost:8080/checkout/finalizar',
    JSON.stringify({ orderId: id, clienteRef: 'cli-k6', centavos: 18000, skuId: 'FONE-BF' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(res, {
    'sem cpf no corpo': (r) => !r.body.includes('cpf'),
    'estado conhecido': (r) => r.body.includes('estado'),
  });
  sleep(0.1);
}
