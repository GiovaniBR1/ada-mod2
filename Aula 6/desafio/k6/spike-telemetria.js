// Roteiro do spike da Aula 6. A prova que roda no `mvn verify` esta nos testes Java
// (TelemetriaTest e SagaDiagnosticoTest). Este arquivo e o mesmo roteiro para o k6,
// quando a ferramenta estiver instalada. Nao faz parte do laboratorio opcional da aula.
//
// O que tem de acontecer, com a falha de 2% no estoque (8 s):
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
    // O portao e a cauda: menos de 2% das chamadas podem passar de 1 s
    // quando o estoque NAO esta com a falha injetada. Com a falha injetada,
    // este threshold fica vermelho de proposito: e o alerta que queremos ver.
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
