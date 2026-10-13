# Onboarding de um tenant

Passo a passo para colocar um lojista (tenant) no ar: criar o tenant pela API, criar os usuários no Keycloak com o atributo `tenant_id` e os papéis, e gerar a credencial de webhook. Os comandos usam o ambiente local (`deploy/local/compose.yaml`) e a aplicação em `http://localhost:8080`.

## Pré-requisitos

- Ambiente local no ar (`docker compose -f deploy/local/compose.yaml up -d`, serviços `healthy`).
- Aplicação rodando com a chave mestra das credenciais definida:

```bash
./mvnw -DskipTests -DskipITs package
export EASYAUDIT_CREDENTIALS_KEY=$(openssl rand -base64 32)   # guarde: sem ela os segredos já gravados não são decifrados
java -jar app/target/easyaudit-app-0.1.0-SNAPSHOT.jar
```

- `curl` e `python3` (para ler o JSON). Variáveis usadas abaixo:

```bash
KC=http://localhost:8081
API=http://localhost:8080
token() {  # token <usuário> [senha]
  curl -s "$KC/realms/easyaudit/protocol/openid-connect/token" -d client_id=easyaudit-api \
    -d grant_type=password -d username="$1" -d password="${2:-senha123}" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])"
}
json() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }
```

## 1. Criar o tenant (PLATFORM_ADMIN)

Use um usuário com o papel `PLATFORM_ADMIN` (no ambiente local, `admin.plataforma`). Informe o nome, o `slug` (único) e ao menos um CNPJ completo ou raiz (numérico ou alfanumérico).

```bash
PLATFORM_TOKEN=$(token admin.plataforma)
TENANT_ID=$(curl -s -X POST "$API/admin/v1/tenants" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Loja Onboarding","slug":"loja-onboarding","cnpjs":["45.723.174/0001-10"]}' | json "['id']")
echo "$TENANT_ID"
```

Respostas esperadas: `201` com o `id` do tenant; `409` se o slug ou o CNPJ (ou a raiz) já existir; `422` se um dígito verificador for inválido.

## 2. Criar os usuários no Keycloak

Cada usuário precisa do atributo `tenant_id` com o `id` do passo 1 e de pelo menos um papel do realm (`TENANT_ADMIN`, `AUDITOR`, `FINANCE` ou `VIEWER`). O mapper do client `easyaudit-api` copia o atributo para a claim `tenant_id` do token.

```bash
KC_ADMIN=$(curl -s "$KC/realms/master/protocol/openid-connect/token" -d client_id=admin-cli \
  -d grant_type=password -d username=admin -d password=admin | json "['access_token']")

curl -s -X POST "$KC/admin/realms/easyaudit/users" -H "Authorization: Bearer $KC_ADMIN" \
  -H 'Content-Type: application/json' -d "{
    \"username\":\"admin.onboarding\",\"enabled\":true,\"emailVerified\":true,
    \"email\":\"admin.onboarding@example.com\",\"firstName\":\"Admin\",\"lastName\":\"Onboarding\",
    \"attributes\":{\"tenant_id\":[\"$TENANT_ID\"]},
    \"credentials\":[{\"type\":\"password\",\"value\":\"senha123\",\"temporary\":false}]}"

USER_ID=$(curl -s "$KC/admin/realms/easyaudit/users?username=admin.onboarding&exact=true" \
  -H "Authorization: Bearer $KC_ADMIN" | json "[0]['id']")
ROLE=$(curl -s "$KC/admin/realms/easyaudit/roles/TENANT_ADMIN" -H "Authorization: Bearer $KC_ADMIN")
curl -s -X POST "$KC/admin/realms/easyaudit/users/$USER_ID/role-mappings/realm" \
  -H "Authorization: Bearer $KC_ADMIN" -H 'Content-Type: application/json' -d "[$ROLE]"
```

Repita para os demais usuários, trocando o `username` e o papel. Em produção, use o console do Keycloak ou o provisionamento da sua identidade; a regra é a mesma: atributo `tenant_id` + papéis.

Confira que o token traz a claim e o papel:

```bash
TENANT_TOKEN=$(token admin.onboarding)
echo "$TENANT_TOKEN" | cut -d. -f2 | python3 -c "
import sys,base64,json; s=sys.stdin.read().strip(); s+='='*(-len(s)%4)
d=json.loads(base64.urlsafe_b64decode(s)); print(d['tenant_id'], d['realm_access']['roles'])"
```

Um usuário sem a claim `tenant_id` recebe `403` (`TENANT_CLAIM_MISSING`) em `/api/**`.

## 3. Cadastrar CNPJs adicionais (TENANT_ADMIN)

```bash
curl -s -X POST "$API/api/v1/tenant/cnpjs" -H "Authorization: Bearer $TENANT_TOKEN" \
  -H 'Content-Type: application/json' -d '{"cnpj":"12.ABC.345/01DE-35"}'
curl -s "$API/api/v1/tenant/cnpjs" -H "Authorization: Bearer $TENANT_TOKEN"
```

## 4. Gerar a credencial de webhook (TENANT_ADMIN)

```bash
curl -s -X POST "$API/api/v1/tenant/integration-credentials" \
  -H "Authorization: Bearer $TENANT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"description":"Webhook Intelipost"}'
```

A resposta traz o `keyId` e o `secret`. **O segredo aparece só nesta resposta**; anote-o agora. As listagens seguintes (`GET /api/v1/tenant/integration-credentials`) mostram apenas `keyId`, descrição, data e status. Para invalidar, `POST /api/v1/tenant/integration-credentials/{keyId}/revoke` (a credencial passa a `REVOKED` e os webhooks que a usam recebem `401`).

### Assinando as chamadas de webhook

Cada chamada a `/webhooks/**` leva três headers:

| Header | Valor |
|---|---|
| `X-EasyAudit-Key-Id` | o `keyId` da credencial |
| `X-EasyAudit-Timestamp` | epoch em segundos (tolerância de 5 minutos) |
| `X-EasyAudit-Signature` | `sha256=` + HMAC-SHA256 hexadecimal de `"<timestamp>.<corpo bruto>"` com o segredo |

```bash
TS=$(date +%s)
BODY='{"exemplo":true}'
SIG=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')
# headers: X-EasyAudit-Key-Id: $KEY_ID / X-EasyAudit-Timestamp: $TS / X-EasyAudit-Signature: sha256=$SIG
```

O tenant é sempre o dono da credencial; ele não é informado na chamada.
