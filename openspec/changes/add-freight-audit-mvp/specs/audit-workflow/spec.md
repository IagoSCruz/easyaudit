# Spec Delta

## Purpose

Controlar o ciclo de vida de cada caso de auditoria, do resultado automático até a decisão do lojista (aprovar, contestar, resolver, reabrir) e o cancelamento, registrando um histórico imutável de quem fez o quê e quando.

## ADDED Requirements

### Requirement: Estados do caso
O caso de auditoria SHALL estar sempre em um destes estados: `PENDING_AUDIT`, `COMPLIANT`, `DIVERGENT`, `NOT_AUDITABLE`, `DISPUTED`, `APPROVED`, `SCHEDULED_FOR_PAYMENT`, `PAID` ou `CANCELLED`. Qualquer ação fora das transições permitidas MUST ser recusada com 409, informando o estado atual.

#### Scenario: Ação inválida para o estado
- **WHEN** um `AUDITOR` tenta contestar um caso em `PAID`
- **THEN** o sistema responde 409 com o estado atual `PAID` e o caso não muda

### Requirement: Estado a partir do resultado da auditoria
Ao concluir uma revisão de auditoria, o sistema SHALL levar o caso de `PENDING_AUDIT`, `COMPLIANT`, `DIVERGENT` ou `NOT_AUDITABLE` para o estado igual à classificação da revisão.

#### Scenario: Reauditoria muda o estado
- **WHEN** um caso `NOT_AUDITABLE` é reauditado e a nova revisão é `DIVERGENT`
- **THEN** o caso passa para `DIVERGENT`

### Requirement: Aprovação automática
Quando a classificação é `COMPLIANT` e a política aplicada tem aprovação automática ligada, o sistema SHALL aprovar o caso automaticamente, com valor aprovado igual ao valor cobrado e o ator `SYSTEM` no histórico.

#### Scenario: Conforme com aprovação automática
- **WHEN** um caso é classificado como `COMPLIANT` com valor cobrado de 66.02 e a política tem aprovação automática ligada
- **THEN** o caso vai para `APPROVED` com valor aprovado 66.02, e o histórico mostra a transição feita por `SYSTEM`

#### Scenario: Aprovação automática desligada
- **WHEN** um caso é classificado como `COMPLIANT` e a política tem aprovação automática desligada
- **THEN** o caso fica em `COMPLIANT` esperando decisão manual

### Requirement: Aprovação manual
Um `AUDITOR` SHALL poder aprovar casos em `COMPLIANT`, `DIVERGENT` ou `NOT_AUDITABLE`, informando um valor aprovado entre zero e o valor cobrado. Justificativa MUST ser obrigatória quando o caso não é `COMPLIANT` ou quando o valor aprovado é diferente do cobrado.

#### Scenario: Aprovação com glosa
- **WHEN** um `AUDITOR` aprova um caso `DIVERGENT` cobrado em 103.00 com valor aprovado 100.00 e a justificativa "Glosa conforme tabela"
- **THEN** o caso vai para `APPROVED` com valor aprovado 100.00 e economia de 3.00

#### Scenario: Divergente aprovado sem justificativa
- **WHEN** um `AUDITOR` aprova um caso `DIVERGENT` sem justificativa
- **THEN** o sistema responde 422 e o caso não muda

#### Scenario: Valor aprovado acima do cobrado
- **WHEN** um `AUDITOR` informa um valor aprovado maior que o valor cobrado
- **THEN** o sistema responde 422

### Requirement: Aprovação em lote
Um `AUDITOR` SHALL poder aprovar até 500 casos em uma única requisição, todos com valor aprovado igual ao cobrado e uma justificativa comum opcional. A resposta MUST trazer o resultado de cada caso, e falhas individuais MUST NOT impedir a aprovação dos outros.

#### Scenario: Lote com caso inválido
- **WHEN** um `AUDITOR` aprova em lote 3 casos `COMPLIANT` e 1 caso `PAID`
- **THEN** os 3 casos `COMPLIANT` vão para `APPROVED` e o caso `PAID` aparece com erro de transição inválida

### Requirement: Contestação
Um `AUDITOR` SHALL poder contestar casos em `COMPLIANT`, `DIVERGENT` ou `NOT_AUDITABLE`, informando a categoria (`VALUE_ABOVE_REFERENCE`, `DUPLICATE_CHARGE`, `WEIGHT_DIVERGENCE`, `UNDUE_COMPONENT` ou `OTHER`), uma descrição e o valor que o lojista considera devido. O caso MUST ir para `DISPUTED`.

#### Scenario: Contestação de duplicidade
- **WHEN** um `AUDITOR` contesta um caso `DIVERGENT` com a categoria `DUPLICATE_CHARGE`, a descrição "NF-e já faturada no CT-e 1234" e o valor devido 0.00
- **THEN** o caso vai para `DISPUTED` e a contestação fica registrada com esses dados

### Requirement: Resolução e desistência da contestação
Um `AUDITOR` SHALL poder resolver um caso `DISPUTED` informando o valor acordado (entre zero e o cobrado) e uma nota, e o caso MUST ir para `APPROVED` com esse valor. O `AUDITOR` também SHALL poder desistir da contestação, e o caso MUST voltar ao estado da última classificação de auditoria.

#### Scenario: Transportadora aceita a glosa
- **WHEN** um `AUDITOR` resolve um caso `DISPUTED` cobrado em 103.00 com valor acordado 100.00 e a nota "Transportadora concedeu desconto na fatura"
- **THEN** o caso vai para `APPROVED` com valor aprovado 100.00

#### Scenario: Desistência
- **WHEN** um `AUDITOR` desiste da contestação de um caso cuja última classificação foi `DIVERGENT`
- **THEN** o caso volta para `DIVERGENT`

### Requirement: Reabertura
Um `AUDITOR` SHALL poder reabrir um caso `APPROVED` que ainda não está em lote de pagamento, informando um motivo. O caso MUST voltar ao estado da última classificação de auditoria, e o valor aprovado MUST ser descartado.

#### Scenario: Reabrir aprovação automática
- **WHEN** um `AUDITOR` reabre, com o motivo "Revisar peso", um caso aprovado automaticamente
- **THEN** o caso volta para `COMPLIANT`, sem valor aprovado

#### Scenario: Reabrir caso em lote
- **WHEN** um `AUDITOR` tenta reabrir um caso em `SCHEDULED_FOR_PAYMENT`
- **THEN** o sistema responde 409

### Requirement: Cancelamento por cancelamento ou substituição do CT-e
Quando o CT-e do caso é cancelado ou substituído, o sistema SHALL levar o caso para `CANCELLED`, com o motivo `CTE_CANCELLED` ou `CTE_SUBSTITUTED`, a partir de qualquer estado exceto `PAID`. Se o caso já estiver `PAID`, ele MUST continuar `PAID` e receber o alerta `CANCELLED_AFTER_PAYMENT`.

#### Scenario: Cancelamento de caso aprovado
- **WHEN** chega o cancelamento do CT-e de um caso `APPROVED`
- **THEN** o caso vai para `CANCELLED` com o motivo `CTE_CANCELLED`

#### Scenario: Cancelamento após pagamento
- **WHEN** chega o cancelamento do CT-e de um caso `PAID`
- **THEN** o caso continua `PAID` e recebe o alerta `CANCELLED_AFTER_PAYMENT`, que aparece nas consultas

### Requirement: Histórico imutável
O sistema SHALL registrar cada transição com estado de origem, estado de destino, ator (usuário ou `SYSTEM`), data/hora, ação, justificativa ou nota e valores envolvidos. O histórico MUST NOT poder ser alterado nem apagado por nenhuma API.

#### Scenario: Consulta do histórico
- **WHEN** um caso passou por `PENDING_AUDIT` → `DIVERGENT` → `DISPUTED` → `APPROVED`
- **THEN** o detalhe do caso mostra as 3 transições em ordem cronológica, cada uma com ator, data e nota

### Requirement: Concorrência de ações
O sistema MUST evitar que duas ações simultâneas sobre o mesmo caso sejam aplicadas a partir do mesmo estado. Toda ação manual MUST informar a versão do caso que o usuário viu, e uma versão desatualizada MUST ser recusada com 409.

#### Scenario: Dois auditores agem ao mesmo tempo
- **WHEN** dois `AUDITOR`s enviam ações para o mesmo caso informando a mesma versão
- **THEN** só a primeira é aplicada e a segunda recebe 409

### Requirement: Publicação das mudanças de estado
O sistema SHALL publicar um evento de integração `audit.status-changed` em cada transição de estado, com caso, estados de origem e destino, ator, data e valor aprovado quando houver, seguindo o contrato publicado.

#### Scenario: Evento de aprovação
- **WHEN** um caso `DIVERGENT` é aprovado
- **THEN** é publicado `audit.status-changed` com origem `DIVERGENT`, destino `APPROVED` e o valor aprovado
