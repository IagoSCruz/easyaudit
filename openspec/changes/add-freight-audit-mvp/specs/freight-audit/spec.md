# Spec Delta

## Purpose

Auditar cada CT-e recebido: definir o valor de referência (tabela de frete e/ou cotação), comparar com o valor cobrado aplicando as políticas de tolerância do tenant, rodar as verificações complementares e classificar o resultado. Essa classificação alimenta o workflow de aprovação.

## ADDED Requirements

### Requirement: Caso de auditoria por CT-e
O sistema SHALL criar exatamente um caso de auditoria para cada CT-e normalizado do tenant (normal, complementar ou substituto), mesmo que a notificação de normalização chegue mais de uma vez. A primeira auditoria MUST ser feita automaticamente logo após a criação do caso.

#### Scenario: Notificação duplicada
- **WHEN** a notificação de normalização do mesmo CT-e é recebida duas vezes
- **THEN** existe um único caso de auditoria para esse CT-e

### Requirement: Valor de referência pela tabela
O sistema SHALL calcular o valor esperado com a tabela vigente da transportadora emitente, usando: CEP de destino e de origem do CT-e, peso real do CT-e (ou, se ausente, a soma dos pesos brutos das NF-es vinculadas), peso cubado e volume do CT-e, valor da carga do CT-e (ou, se ausente, a soma dos valores das NF-es) e alíquota de ICMS do CT-e.

#### Scenario: CT-e com tabela vigente
- **WHEN** um CT-e de 15 kg taxados, carga de 1000.00 e ICMS de 12% é auditado com a tabela T da spec de cálculo
- **THEN** o valor esperado pela tabela é 66.02, com o detalhamento por componente registrado no resultado

#### Scenario: CT-e sem peso
- **WHEN** o CT-e não informa peso real e as 2 NF-es vinculadas têm pesos brutos de 3.000 e 2.000 kg
- **THEN** o peso real usado no cálculo é 5.000 kg

### Requirement: Valor de referência pela cotação
O sistema SHALL usar como valor cotado a soma das cotações aplicáveis das NF-es vinculadas ao CT-e, desde que todas as NF-es referenciadas estejam recebidas e tenham cotação aplicável. Caso contrário, MUST considerar que não há valor cotado.

#### Scenario: CT-e com uma NF-e cotada
- **WHEN** o CT-e referencia só a NF-e K, que tem cotação aplicável de 64.90
- **THEN** o valor cotado do CT-e é 64.90

#### Scenario: Cotação parcial
- **WHEN** o CT-e referencia as NF-es K1 e K2 e só K1 tem cotação aplicável
- **THEN** não há valor cotado para o CT-e

### Requirement: Políticas de tolerância
O sistema SHALL manter uma política padrão por tenant e permitir políticas por transportadora, que substituem só os campos preenchidos. Campos: tolerância absoluta (R$), tolerância percentual, aprovação automática dos conformes, tolerância de peso (%) e tolerância de valor de carga (%). Só `TENANT_ADMIN` MUST poder alterá-las.

#### Scenario: Política padrão inicial
- **WHEN** um tenant é criado
- **THEN** ele recebe a política padrão: tolerância absoluta 0.00, tolerância percentual 0%, aprovação automática ligada, tolerância de peso 5% e tolerância de valor de carga 1%

#### Scenario: Sobrescrita por transportadora
- **WHEN** a política do tenant tem tolerância percentual de 1% e a da transportadora X define 2,5%, sem definir outros campos
- **THEN** os CT-es de X são auditados com 2,5% de tolerância percentual e com os demais campos da política do tenant

#### Scenario: Valor inválido
- **WHEN** um `TENANT_ADMIN` define uma tolerância percentual de 120%
- **THEN** o sistema responde 422

### Requirement: Regra de tolerância
O sistema SHALL calcular a cobrança a maior como valor cobrado (`vTPrest`) menos o valor de referência. A cobrança está dentro da tolerância quando é menor ou igual ao maior entre a tolerância absoluta e o percentual sobre o valor de referência. Cobrança abaixo da referência MUST ser conforme e registrada como `UNDERCHARGE`.

#### Scenario: Dentro da tolerância percentual
- **WHEN** a referência é 100.00, o cobrado é 101.50 e a política tem tolerância absoluta 1.00 e percentual 2%
- **THEN** a diferença de 1.50 está dentro do limite de 2.00 e a verificação passa

#### Scenario: Fora da tolerância
- **WHEN** a referência é 100.00, o cobrado é 103.00 e a política tem tolerância absoluta 1.00 e percentual 2%
- **THEN** a diferença de 3.00 passa do limite de 2.00 e a verificação falha com divergência de 3.00

#### Scenario: Cobrança abaixo da referência
- **WHEN** a referência é 100.00 e o cobrado é 95.00
- **THEN** a verificação passa e o resultado registra `UNDERCHARGE` de 5.00

### Requirement: Verificações bloqueantes
O sistema SHALL rodar as verificações bloqueantes: `TOTAL_VS_TABLE` e `TOTAL_VS_QUOTE` (cobrança fora da tolerância), `DUPLICATE_NFE` (NF-e já referenciada por outro CT-e normal válido da mesma raiz de transportadora), `COMPLEMENTARY_CHARGE` (todo CT-e complementar) e `CANCELLED_NFE` (NF-e vinculada cancelada).

#### Scenario: NF-e cobrada em duplicidade
- **WHEN** a NF-e K já é referenciada pelo CT-e normal C1, não cancelado, e chega o CT-e normal C2 da mesma transportadora também referenciando K
- **THEN** a auditoria de C2 tem a falha `DUPLICATE_NFE`, que aponta para C1

#### Scenario: CT-e substituto não é duplicidade
- **WHEN** o CT-e substituto C2 referencia a mesma NF-e que o CT-e C1, que ele substitui
- **THEN** a verificação `DUPLICATE_NFE` passa

#### Scenario: CT-e complementar
- **WHEN** é auditado um CT-e complementar
- **THEN** a auditoria tem a falha `COMPLEMENTARY_CHARGE`, que referencia o CT-e complementado

### Requirement: Verificações de alerta
O sistema SHALL rodar verificações que geram só alertas, sem bloquear: `WEIGHT_MISMATCH` (peso do CT-e diferente da soma dos pesos brutos das NF-es além da tolerância de peso), `CARGO_VALUE_MISMATCH` (valor da carga diferente da soma das NF-es além da tolerância), `CARRIER_MISMATCH` (transportadora da NF-e com outra raiz de CNPJ) e `FOB_FREIGHT` (NF-e com frete por conta do destinatário).

#### Scenario: Peso divergente
- **WHEN** o CT-e declara 120 kg, a NF-e vinculada tem peso bruto de 100 kg e a tolerância de peso é 5%
- **THEN** a auditoria gera o alerta `WEIGHT_MISMATCH` com os dois pesos, sem mudar a classificação

#### Scenario: Frete FOB cobrado do lojista
- **WHEN** a NF-e vinculada tem modalidade de frete 1 (por conta do destinatário) e o tomador do CT-e é o tenant
- **THEN** a auditoria gera o alerta `FOB_FREIGHT`

### Requirement: Classificação do resultado
O sistema SHALL classificar cada auditoria como `DIVERGENT` (pelo menos uma verificação bloqueante falhou), `NOT_AUDITABLE` (nenhuma falha bloqueante e nenhum valor de referência) ou `COMPLIANT` (há valor de referência e nenhuma falha bloqueante). Sem tabela, o resultado MUST informar o motivo (`NO_TABLE` ou `ROUTE_NOT_COVERED`) e NF-es ainda não recebidas (`PENDING_NFE`).

#### Scenario: Conforme pela tabela
- **WHEN** a tabela dá 66.02, o cobrado é 66.02 e não há outras falhas
- **THEN** a auditoria é `COMPLIANT`

#### Scenario: Sem tabela e sem cotação
- **WHEN** a transportadora não tem tabela vigente, não há valor cotado e nenhuma verificação bloqueante falhou
- **THEN** a auditoria é `NOT_AUDITABLE` com o motivo `NO_TABLE`

#### Scenario: Só cotação disponível
- **WHEN** não há tabela vigente, o valor cotado é 64.90 e o cobrado é 64.90
- **THEN** a auditoria é `COMPLIANT`, com `TOTAL_VS_QUOTE` aprovada e o motivo `NO_TABLE` informado

### Requirement: Comparação por componente
O sistema SHALL registrar no resultado, para cada código de componente, o valor cobrado no CT-e (somando os componentes com o mesmo código), o valor calculado pela tabela e a diferença. Essa comparação é informativa e MUST NOT mudar a classificação.

#### Scenario: GRIS cobrado acima da tabela
- **WHEN** o CT-e cobra GRIS 4.00, a tabela calcula 2.00 e o total está dentro da tolerância
- **THEN** o resultado mostra a diferença de 2.00 em `GRIS` e a classificação continua `COMPLIANT`

### Requirement: Snapshot e revisões da auditoria
Cada execução SHALL gerar uma revisão numerada e imutável do resultado, com a política aplicada, a versão da tabela, a cotação usada e os valores de entrada. Mudanças posteriores na política ou na tabela MUST NOT alterar revisões já registradas.

#### Scenario: Política alterada depois da auditoria
- **WHEN** a tolerância do tenant muda de 0% para 5% depois de um CT-e ter sido auditado
- **THEN** a revisão existente continua mostrando 0%, e só uma nova auditoria usa 5%

### Requirement: Reauditoria
O sistema SHALL reauditar automaticamente casos em `PENDING_AUDIT`, `COMPLIANT`, `DIVERGENT` ou `NOT_AUDITABLE` quando: um vínculo de NF-e do CT-e é resolvido, a cotação aplicável muda, ou uma tabela é ativada com vigência que inclui a emissão do CT-e. Um `AUDITOR` MUST poder pedir a reauditoria manual de um caso nesses estados. Casos em outros estados não são reauditados.

#### Scenario: NF-e chega depois
- **WHEN** um caso `NOT_AUDITABLE` com `PENDING_NFE` tem o vínculo da NF-e resolvido
- **THEN** é criada uma nova revisão com os dados da NF-e

#### Scenario: Tabela ativada depois dos CT-es
- **WHEN** existem 10 casos `NOT_AUDITABLE` (`NO_TABLE`) da transportadora X emitidos em outubro e é ativada uma tabela de X vigente desde 1º de outubro
- **THEN** os 10 casos são reauditados com essa tabela

#### Scenario: Caso aprovado não é reauditado
- **WHEN** chega uma nova cotação para a NF-e de um caso já `APPROVED`
- **THEN** o caso continua com a mesma revisão e o mesmo estado

### Requirement: Publicação do resultado
O sistema SHALL publicar um evento de integração `audit.completed` para cada revisão de auditoria, com o caso, a revisão, a classificação, os valores cobrado e de referência e as verificações que falharam, seguindo o contrato publicado.

#### Scenario: Evento de auditoria
- **WHEN** uma auditoria termina como `DIVERGENT`
- **THEN** é publicado `audit.completed` com a classificação `DIVERGENT` e a lista de falhas
