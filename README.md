# EasyAudit

Plataforma de auditoria de frete (CT-e) em monorepo modular: Java 25, Spring Boot 4.1, Spring Modulith, PostgreSQL 17, RabbitMQ 4 e Keycloak 26.

## Pré-requisitos

- JDK 25
- Docker com Docker Compose v2
- `curl` (e opcionalmente `python3` ou `jq` para ler tokens)

O Maven não precisa estar instalado: use o wrapper (`./mvnw`).

## Build

```bash
./mvnw verify
```

Constrói todos os módulos e roda os testes (inclusive a verificação de módulos do Spring Modulith).

## Ambiente local

```bash
docker compose -f deploy/local/compose.yaml up -d
docker compose -f deploy/local/compose.yaml ps   # aguarde os 3 serviços "healthy"
```

| Serviço    | Endereço                 | Credenciais                                        |
|------------|--------------------------|----------------------------------------------------|
| PostgreSQL | `localhost:5432`         | banco `easyaudit`; superusuário `postgres`/`postgres`, dono `easyaudit_owner`/`easyaudit_owner`, aplicação `easyaudit_app`/`easyaudit_app` |
| RabbitMQ   | `localhost:5672`, console em <http://localhost:15672> | `easyaudit`/`easyaudit` |
| Keycloak   | <http://localhost:8081>  | console: `admin`/`admin`; realm `easyaudit`        |

Para parar: `docker compose -f deploy/local/compose.yaml down` (acrescente `-v` para apagar os dados).

## Tokens de teste

O realm `easyaudit` é importado de `deploy/local/keycloak/easyaudit-realm.json`, com o client público `easyaudit-api`. Todos os usuários têm a senha `senha123`.

| Usuário            | Papel          | Tenant (`tenant_id`)                   |
|--------------------|----------------|----------------------------------------|
| `admin.plataforma` | PLATFORM_ADMIN | nenhum                                 |
| `admin.a`          | TENANT_ADMIN   | `11111111-1111-4111-8111-111111111111` |
| `auditor.a`        | AUDITOR        | `11111111-1111-4111-8111-111111111111` |
| `financeiro.a`     | FINANCE        | `11111111-1111-4111-8111-111111111111` |
| `leitor.a`         | VIEWER         | `11111111-1111-4111-8111-111111111111` |
| `admin.b`          | TENANT_ADMIN   | `22222222-2222-4222-8222-222222222222` |
| `auditor.b`        | AUDITOR        | `22222222-2222-4222-8222-222222222222` |

```bash
curl -s http://localhost:8081/realms/easyaudit/protocol/openid-connect/token \
  -d client_id=easyaudit-api -d grant_type=password \
  -d username=auditor.a -d password=senha123 | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])"
```

O token traz a claim `tenant_id` e os papéis em `realm_access.roles`.

## Roteiro de testes ponta a ponta (Walkthrough)

Para validar o fluxo completo do MVP localmente contra os serviços do Docker Compose:

1. Suba os serviços:
   ```bash
   docker compose -f deploy/local/compose.yaml up -d
   ```
2. Inicie a aplicação com a chave de credenciais:
   ```bash
   export EASYAUDIT_CREDENTIALS_KEY="sLiLfgX+mcAzrDvOzyoOPnmAxdbgPDz3bnmG1zJkGlk="
   ./mvnw -pl app spring-boot:run
   ```
3. Execute o roteiro interativo via REST Client / IntelliJ HTTP Client em [`docs/local-walkthrough.http`](docs/local-walkthrough.http) ou execute o script automatizado:
   ```bash
   ./docs/local-walkthrough.sh
   ```

O roteiro reproduz o fluxo ponta a ponta completo:
- Onboarding do tenant (`PLATFORM_ADMIN`) e vínculo do tenant aos usuários no Keycloak
- Emissão de credencial de webhook (`TENANT_ADMIN`)
- Cadastro de transportadora e importação/ativação da tabela de frete
- Ingestão de NF-e e CT-e assinados via webhook HMAC
- Ingestão de cotação via webhook HMAC
- Processamento assíncrono e auditoria do caso divergente (`AUDITOR`)
- Abertura e resolução de contestação com o transportador
- Criação, exportação CSV (BOM UTF-8 e `;`) e baixa manual do lote de pagamento (`FINANCE`)
- Consulta aos indicadores consolidados e ranking de transportadoras no Dashboard

