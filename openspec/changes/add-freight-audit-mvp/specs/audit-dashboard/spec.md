# Spec Delta

## Purpose

Expor as consultas e os indicadores que o painel de auditoria do lojista usa: lista filtrável de casos, detalhe completo de cada caso, indicadores consolidados por período e ranking de transportadoras.

## ADDED Requirements

### Requirement: Listagem de casos
O sistema SHALL listar os casos do tenant em `GET /api/v1/audits`, paginada (até 100 por página) e ordenável por data de emissão, valor cobrado ou divergência. Filtros: estados, transportadora, período de emissão, UF de destino, código de verificação com falha, divergência mínima, alerta e chave ou número do CT-e.

#### Scenario: Divergentes de uma transportadora
- **WHEN** o usuário filtra pelo estado `DIVERGENT`, pela transportadora X e pelo período de 2026-10-01 a 2026-10-31
- **THEN** a resposta traz só os casos `DIVERGENT` de X emitidos nesse período, com o total de registros e a paginação

#### Scenario: Página acima do limite
- **WHEN** o usuário pede uma página com 500 itens
- **THEN** o sistema responde 400 indicando o máximo de 100

#### Scenario: Conteúdo de cada item
- **WHEN** o usuário lista casos
- **THEN** cada item traz id, versão, número e chave do CT-e, transportadora, data de emissão, UF de destino, valor cobrado, valor de referência, divergência, estado, verificações com falha e alertas

### Requirement: Detalhe do caso
O sistema SHALL expor em `GET /api/v1/audits/{id}` o resumo canônico do CT-e, as NF-es vinculadas e as pendentes, a cotação usada, a revisão vigente (com cálculo por componente, comparação por componente e todas as verificações), as revisões anteriores, o histórico, a contestação, o lote de pagamento e os alertas.

#### Scenario: Caso contestado
- **WHEN** o usuário abre um caso `DISPUTED`
- **THEN** a resposta traz os dados da contestação, a revisão que a motivou, o histórico até a contestação e a versão atual do caso

### Requirement: Indicadores consolidados
O sistema SHALL expor em `GET /api/v1/dashboard/summary`, para um período de emissão e opcionalmente uma transportadora: quantidade e valor cobrado por estado, total cobrado, total de referência, divergência total dos casos `DIVERGENT`, total aprovado, economia (cobrado menos aprovado nos casos aprovados, em lote ou pagos) e taxa de conformidade.

#### Scenario: Cálculo da economia
- **WHEN** no período há 2 casos aprovados (cobrado 103.00 e aprovado 100.00; cobrado 66.02 e aprovado 66.02) e 1 caso pago (cobrado 50.00 e aprovado 45.00)
- **THEN** o total aprovado é 211.02 e a economia é 8.00

#### Scenario: Taxa de conformidade
- **WHEN** no período há 8 casos cuja classificação vigente é `COMPLIANT`, 2 `DIVERGENT` e 5 `NOT_AUDITABLE`
- **THEN** a taxa de conformidade é 80%, calculada sobre os casos classificados como `COMPLIANT` ou `DIVERGENT`

#### Scenario: Período sem dados
- **WHEN** não há casos no período informado
- **THEN** a resposta traz zeros em todas as quantidades e valores, e a taxa de conformidade vem nula

### Requirement: Ranking de transportadoras
O sistema SHALL expor em `GET /api/v1/dashboard/carriers`, para um período de emissão, uma linha por transportadora com quantidade de casos, total cobrado, quantidade e valor das divergências, taxa de divergência e economia, ordenada pelo valor de divergência decrescente.

#### Scenario: Ordenação do ranking
- **WHEN** no período a transportadora A tem divergência total de 500.00 e a B de 1200.00
- **THEN** B aparece antes de A

### Requirement: Exportação da listagem
O sistema SHALL exportar em CSV (UTF-8 com BOM, separador `;`, decimais com vírgula) os casos que atendem aos mesmos filtros da listagem, limitados a 50.000 linhas. Se o filtro passar do limite, MUST responder 422 pedindo um filtro mais restrito.

#### Scenario: Exportação acima do limite
- **WHEN** o filtro retorna 80.000 casos
- **THEN** o sistema responde 422 informando o limite de 50.000 linhas

### Requirement: Atualização dos indicadores
As consultas desta capability SHALL refletir as auditorias e transições de estado em até 60 segundos após elas acontecerem.

#### Scenario: Aprovação refletida
- **WHEN** um caso é aprovado
- **THEN** em até 60 segundos a listagem e os indicadores mostram o caso como aprovado
