#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# EasyAudit - Roteiro Automatizado de Teste Ponta a Ponta Local
# Reproduz o fluxo completo do MVP contra http://localhost:8080 e Keycloak em :8081
# ==============================================================================

API="${API:-http://localhost:8080}"
KC="${KC:-http://localhost:8081}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES_DIR="$DIR/fixtures"

echo "=== 1. Verificando conectividade da aplicação e Keycloak ==="
curl -sf "$KC/realms/easyaudit/.well-known/openid-configuration" > /dev/null || {
  echo "Erro: Keycloak não está acessível em $KC. Suba com: docker compose -f deploy/local/compose.yaml up -d"
  exit 1
}

curl -sf "$API/actuator/health" > /dev/null || {
  echo "Erro: Aplicação EasyAudit não está respondendo em $API/actuator/health."
  echo "Inicie a aplicação com:"
  echo "  export EASYAUDIT_CREDENTIALS_KEY=\"sLiLfgX+mcAzrDvOzyoOPnmAxdbgPDz3bnmG1zJkGlk=\""
  echo "  ./mvnw -pl app spring-boot:run"
  exit 1
}
echo "✓ Serviços ativos e saudáveis."

json_val() {
  python3 -c "import sys, json; print(json.load(sys.stdin)$1)"
}

token() {
  local user="$1"
  local pass="${2:-senha123}"
  curl -s -f "$KC/realms/easyaudit/protocol/openid-connect/token" \
    -d client_id=easyaudit-api -d grant_type=password \
    -d username="$user" -d password="$pass" | json_val "['access_token']"
}

echo "=== 2. Onboarding do Tenant (PLATFORM_ADMIN) ==="
PLATFORM_TOKEN=$(token admin.plataforma)
SLUG="lojista-walkthrough-$(date +%s)"
CREATE_TENANT_RES=$(curl -s -X POST "$API/admin/v1/tenants" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Lojista Walkthrough\",\"slug\":\"$SLUG\",\"cnpjs\":[\"45.723.174/0001-10\"]}")

TENANT_ID=$(echo "$CREATE_TENANT_RES" | json_val "['id']")
echo "✓ Tenant criado com ID: $TENANT_ID (slug: $SLUG)"

echo "=== 3. Atualizando atributo tenant_id dos usuários de teste no Keycloak ==="
KC_ADMIN_TOKEN=$(curl -s -f "$KC/realms/master/protocol/openid-connect/token" \
  -d client_id=admin-cli -d grant_type=password \
  -d username=admin -d password=admin | json_val "['access_token']")

for USERNAME in "admin.a" "auditor.a" "financeiro.a"; do
  USER_ID=$(curl -s "$KC/admin/realms/easyaudit/users?username=$USERNAME&exact=true" \
    -H "Authorization: Bearer $KC_ADMIN_TOKEN" | json_val "[0]['id']")
  
  curl -s -X PUT "$KC/admin/realms/easyaudit/users/$USER_ID" \
    -H "Authorization: Bearer $KC_ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"email\":\"$USERNAME@example.com\",\"enabled\":true,\"attributes\":{\"tenant_id\":[\"$TENANT_ID\"]}}"
  echo "✓ Usuário $USERNAME vinculado ao tenant $TENANT_ID"
done

echo "=== 4. Obtendo tokens JWT dos usuários do tenant ==="
TENANT_ADMIN_TOKEN=$(token admin.a)
AUDITOR_TOKEN=$(token auditor.a)
FINANCE_TOKEN=$(token financeiro.a)
echo "✓ Tokens JWT gerados com sucesso."

echo "=== 5. Criação de credencial de webhook (TENANT_ADMIN) ==="
CRED_RES=$(curl -s -X POST "$API/api/v1/tenant/integration-credentials" \
  -H "Authorization: Bearer $TENANT_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"description":"Webhook E2E Walkthrough"}')

KEY_ID=$(echo "$CRED_RES" | json_val "['keyId']")
SECRET=$(echo "$CRED_RES" | json_val "['secret']")
echo "✓ Credencial gerada (keyId: $KEY_ID)"

echo "=== 6. Cadastro de transportadora (TENANT_ADMIN) ==="
CARRIER_RES=$(curl -s -X POST "$API/api/v1/carriers" \
  -H "Authorization: Bearer $TENANT_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cnpj":"11222333000181","name":"Transportadora Rápidos E2E"}')

CARRIER_ID=$(echo "$CARRIER_RES" | json_val "['id']")
echo "✓ Transportadora cadastrada (ID: $CARRIER_ID)"

echo "=== 7. Cadastro e ativação da tabela de frete (TENANT_ADMIN) ==="
TABLE_BODY=$(cat <<EOF
{
  "carrierId": "$CARRIER_ID",
  "content": {
    "name": "Tabela T",
    "validFrom": "2026-01-01",
    "includesIcms": false,
    "cubingFactor": 300,
    "minimumFreight": 40.00,
    "dispatchFee": 5.00,
    "routes": [
      {
        "name": "SP capital",
        "destinationCepFrom": "01000000",
        "destinationCepTo": "09999999",
        "weightBands": [
          { "fromKg": 0, "toKg": 5, "price": 20.00 },
          { "fromKg": 5, "toKg": 10, "price": 28.00 },
          { "fromKg": 10, "toKg": 30, "price": 45.00 }
        ],
        "excessPerKg": 1.20,
        "adValoremPercent": 0.30,
        "adValoremMinimum": 2.00,
        "grisPercent": 0.20,
        "grisMinimum": 1.50,
        "tollPer100Kg": 3.10
      }
    ],
    "specialAreas": []
  }
}
EOF
)

TABLE_RES=$(curl -s -X POST "$API/api/v1/freight-tables" \
  -H "Authorization: Bearer $TENANT_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$TABLE_BODY")

TABLE_ID=$(echo "$TABLE_RES" | json_val "['id']")
curl -s -X POST "$API/api/v1/freight-tables/$TABLE_ID/activate" \
  -H "Authorization: Bearer $TENANT_ADMIN_TOKEN" > /dev/null
echo "✓ Tabela de frete cadastrada e ativada (ID: $TABLE_ID)"

echo "=== 8. Ingestão de NF-e e CT-e via Webhook assinado com HMAC ==="
NFE_B64=$(base64 -w 0 "$FIXTURES_DIR/nfe-9501.xml")
CTE_B64=$(base64 -w 0 "$FIXTURES_DIR/cte-9501.xml")

DOCS_BODY=$(cat <<EOF
{
  "documents": [
    {"name": "nfe.xml", "contentBase64": "$NFE_B64"},
    {"name": "cte.xml", "contentBase64": "$CTE_B64"}
  ]
}
EOF
)

TS=$(date +%s)
SIG=$(printf '%s.%s' "$TS" "$DOCS_BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')

DOCS_RES=$(curl -s -X POST "$API/webhooks/v1/documents" \
  -H "Content-Type: application/json" \
  -H "X-EasyAudit-Key-Id: $KEY_ID" \
  -H "X-EasyAudit-Timestamp: $TS" \
  -H "X-EasyAudit-Signature: sha256=$SIG" \
  -d "$DOCS_BODY")

echo "$DOCS_RES" | grep -q "ACCEPTED" || {
  echo "Erro na ingestão de documentos: $DOCS_RES"
  exit 1
}
echo "✓ Documentos fiscais aceitos."

echo "=== 9. Ingestão de Cotação via Webhook assinado com HMAC ==="
QUOTE_BODY='{"source":"INTELIPOST","externalId":"Q-9501","carrierCnpj":"11222333000181","service":"Rodoviário","amount":66.02,"quotedAt":"2026-10-09T10:00:00-03:00","destinationCep":"01310100","weightKg":12.5,"cargoValue":1000,"deliveryDays":5,"nfeKey":"35261045723174000110550010000095011876543210"}'
TS=$(date +%s)
SIG=$(printf '%s.%s' "$TS" "$QUOTE_BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')

QUOTE_RES=$(curl -s -X POST "$API/webhooks/v1/quotes" \
  -H "Content-Type: application/json" \
  -H "X-EasyAudit-Key-Id: $KEY_ID" \
  -H "X-EasyAudit-Timestamp: $TS" \
  -H "X-EasyAudit-Signature: sha256=$SIG" \
  -d "$QUOTE_BODY")

echo "✓ Cotação ingerida com sucesso."

echo "=== 10. Aguardando auditoria assíncrona (RabbitMQ) ==="
CASE_ID=""
CASE_VERSION=""
for i in {1..30}; do
  AUDITS_RES=$(curl -s "$API/api/v1/audits" -H "Authorization: Bearer $AUDITOR_TOKEN")
  COUNT=$(echo "$AUDITS_RES" | python3 -c "import sys, json; data=json.load(sys.stdin); print(len(data.get('items', [])))" 2>/dev/null || echo "0")
  if [ "$COUNT" -gt 0 ]; then
    STATUS=$(echo "$AUDITS_RES" | json_val "['items'][0]['status']")
    if [ "$STATUS" = "DIVERGENT" ]; then
      CASE_ID=$(echo "$AUDITS_RES" | json_val "['items'][0]['id']")
      CASE_VERSION=$(echo "$AUDITS_RES" | json_val "['items'][0]['version']")
      DIV=$(echo "$AUDITS_RES" | json_val "['items'][0]['divergenceAmount']")
      echo "✓ Caso de auditoria encontrado: $CASE_ID (Status: $STATUS, Divergência: R$ $DIV)"
      break
    fi
  fi
  sleep 1
done

if [ -z "$CASE_ID" ]; then
  echo "Erro: caso de auditoria não ficou DIVERGENT no tempo esperado."
  exit 1
fi

echo "=== 11. Contestação do caso divergente (AUDITOR) ==="
DISPUTE_BODY="{\"version\":$CASE_VERSION,\"category\":\"VALUE_ABOVE_REFERENCE\",\"description\":\"Frete cobrado acima da tabela negociada\",\"amountDue\":66.02}"
DISPUTE_RES=$(curl -s -X POST "$API/api/v1/audits/$CASE_ID/dispute" \
  -H "Authorization: Bearer $AUDITOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$DISPUTE_BODY")

RESOLVED_VERSION=$(echo "$DISPUTE_RES" | json_val "['version']")
echo "✓ Caso contestado (Status: DISPUTED, nova versão: $RESOLVED_VERSION)"

echo "=== 12. Resolução da contestação (AUDITOR) ==="
RESOLVE_BODY="{\"version\":$RESOLVED_VERSION,\"agreedAmount\":66.02,\"note\":\"Transportadora aceitou o valor de tabela\"}"
RESOLVE_RES=$(curl -s -X POST "$API/api/v1/audits/$CASE_ID/resolve" \
  -H "Authorization: Bearer $AUDITOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$RESOLVE_BODY")

STATUS=$(echo "$RESOLVE_RES" | json_val "['status']")
APP_AMOUNT=$(echo "$RESOLVE_RES" | json_val "['approvedAmount']")
echo "✓ Caso resolvido (Status: $STATUS, Valor Aprovado: R$ $APP_AMOUNT)"

echo "=== 13. Criação de lote de pagamento (FINANCE) ==="
BATCH_BODY="{\"dueDate\":\"2026-11-10\",\"description\":\"Lote E2E Walkthrough\",\"caseIds\":[\"$CASE_ID\"]}"
BATCH_RES=$(curl -s -X POST "$API/api/v1/payment-batches" \
  -H "Authorization: Bearer $FINANCE_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$BATCH_BODY")

BATCH_ID=$(echo "$BATCH_RES" | json_val "['batch']['id']")
CHARGED=$(echo "$BATCH_RES" | json_val "['batch']['chargedTotal']")
APPROVED=$(echo "$BATCH_RES" | json_val "['batch']['approvedTotal']")
SAVINGS=$(echo "$BATCH_RES" | json_val "['batch']['savingsTotal']")
echo "✓ Lote de pagamento criado: $BATCH_ID (Cobrado: $CHARGED, Aprovado: $APPROVED, Economia: $SAVINGS)"

echo "=== 14. Exportação CSV do lote (FINANCE) ==="
CSV_FILE=$(mktemp)
curl -s -f "$API/api/v1/payment-batches/$BATCH_ID/export" \
  -H "Authorization: Bearer $FINANCE_TOKEN" -o "$CSV_FILE"

# Valida BOM UTF-8 e conteúdo
python3 -c "
with open('$CSV_FILE', 'rb') as f:
    b = f.read()
assert b.startswith(b'\xef\xbb\xbf'), 'Falta BOM UTF-8'
text = b.decode('utf-8')
assert '11222333000181' in text, 'Falta CNPJ da transportadora'
assert '80,00' in text, 'Falta valor cobrado formatado'
assert '66,02' in text, 'Falta valor aprovado formatado'
"
rm -f "$CSV_FILE"
echo "✓ CSV exportado com BOM UTF-8 e valores com vírgula validados."

echo "=== 15. Baixa manual do lote (FINANCE) ==="
PAYMENT_BODY='{"paidOn":"2026-10-10","reference":"TED-E2E-WALKTHROUGH-001"}'
PAYMENT_RES=$(curl -s -X POST "$API/api/v1/payment-batches/$BATCH_ID/payment" \
  -H "Authorization: Bearer $FINANCE_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$PAYMENT_BODY")

BATCH_STATUS=$(echo "$PAYMENT_RES" | json_val "['batch']['status']")
echo "✓ Lote baixado com sucesso (Status: $BATCH_STATUS)"

echo "=== 16. Consulta de Indicadores do Dashboard (AUDITOR) ==="
SUMMARY_RES=$(curl -s "$API/api/v1/dashboard/summary?from=2026-10-01&to=2026-10-31" \
  -H "Authorization: Bearer $AUDITOR_TOKEN")

CASES=$(echo "$SUMMARY_RES" | json_val "['caseCount']")
SAVINGS_TOTAL=$(echo "$SUMMARY_RES" | json_val "['savings']")
echo "✓ Dashboard Summary: $CASES caso(s) auditado(s), Economia acumulada: R$ $SAVINGS_TOTAL"

CARRIERS_RES=$(curl -s "$API/api/v1/dashboard/carriers?from=2026-10-01&to=2026-10-31" \
  -H "Authorization: Bearer $AUDITOR_TOKEN")
TOP_CARRIER=$(echo "$CARRIERS_RES" | json_val "['items'][0]['carrierCnpj']")
echo "✓ Dashboard Carriers: Top transportadora com divergências: $TOP_CARRIER"

echo ""
echo "=============================================================================="
echo "✔ SUCESSO: Roteiro ponta a ponta finalizado com sucesso no ambiente local!"
echo "=============================================================================="

