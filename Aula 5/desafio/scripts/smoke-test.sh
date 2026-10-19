#!/usr/bin/env bash
# Smoke test da IMAGEM construida (nao do jar). Uso: scripts/smoke-test.sh checkout:1.5.0
set -euo pipefail
IMG="${1:?informe a imagem}"
NAME="checkout-smoke-$$"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# 1. Sobe com o mesmo endurecimento de producao: nao-root e filesystem somente leitura.
docker run -d --name "$NAME" --read-only --tmpfs /tmp -p 18080:8080 \
  -e APP_VERSION=smoke -e GIT_SHA=local "$IMG" >/dev/null

# 2. Espera a readiness responder 200 (ate 60 s).
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/actuator/health/readiness || true)
  [ "$code" = "200" ] && break
  sleep 1
done
[ "$code" = "200" ] || { echo "FALHA: readiness=$code"; docker logs "$NAME"; exit 1; }
echo "ok: readiness 200"

# 3. Requisicao de negocio: a rota responde e consulta o banco ja migrado (V1, V3, V4).
#    A imagem nao leva dados de exemplo, entao um pedido inexistente deve dar 404 (e nao 500).
code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/pedidos/999)
[ "$code" = "404" ] || { echo "FALHA: /pedidos/999 respondeu $code"; docker logs "$NAME"; exit 1; }
echo "ok: rota de pedidos responde 404 para pedido inexistente"

# 4. Verificacoes negativas da imagem.
if docker run --rm --entrypoint sh "$IMG" -c true >/dev/null 2>&1; then
  echo "FALHA: a imagem tem shell"; exit 1
fi
echo "ok: sem shell"
user=$(docker inspect --format '{{.Config.User}}' "$IMG")
[ -n "$user" ] && [ "$user" != "root" ] && [ "$user" != "0" ] || { echo "FALHA: roda como root"; exit 1; }
echo "ok: usuario $user"
size=$(docker image inspect --format '{{.Size}}' "$IMG")
echo "tamanho: $((size/1024/1024)) MB"
[ "$size" -lt $((250*1024*1024)) ] || { echo "FALHA: imagem acima de 250 MB"; exit 1; }
echo "SMOKE OK"
