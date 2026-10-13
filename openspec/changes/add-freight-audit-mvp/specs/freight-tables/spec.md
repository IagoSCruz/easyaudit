# Spec Delta

## Purpose

Manter as transportadoras do tenant e as tabelas de frete negociadas com elas (importadas por planilha ou JSON), com validação, versionamento e vigência, para que a auditoria sempre use a tabela que valia na data de emissão do CT-e.

## ADDED Requirements

### Requirement: Cadastro de transportadoras
O sistema SHALL manter, por tenant, transportadoras identificadas por CNPJ (único dentro do tenant), com nome e status ativo/inativo. Um `TENANT_ADMIN` MUST poder criar, editar e inativar transportadoras.

#### Scenario: Cadastro manual
- **WHEN** um `TENANT_ADMIN` cadastra a transportadora "Transportes Rápidos" com um CNPJ válido
- **THEN** a transportadora fica disponível para receber tabelas de frete

#### Scenario: CNPJ repetido no tenant
- **WHEN** um `TENANT_ADMIN` cadastra uma transportadora com um CNPJ que já existe no tenant
- **THEN** o sistema responde 409

### Requirement: Cadastro automático de transportadora
O sistema SHALL cadastrar automaticamente a transportadora emitente de um CT-e normalizado quando o CNPJ ainda não existe no tenant, usando o nome do XML e marcando a origem como automática.

#### Scenario: CT-e de transportadora desconhecida
- **WHEN** é normalizado um CT-e emitido por um CNPJ que não está cadastrado no tenant
- **THEN** a transportadora é criada com o nome do emitente e a marcação `autoRegistered` = true

### Requirement: Sinônimos de componentes por transportadora
O sistema SHALL permitir que um `TENANT_ADMIN` cadastre, para cada transportadora, sinônimos de nomes de componente associados a um código canônico de componente. Os sinônimos MUST valer para os CT-es normalizados depois do cadastro.

#### Scenario: Novo sinônimo
- **WHEN** um `TENANT_ADMIN` associa "SEG. CARGA" a `AD_VALOREM` para a transportadora X
- **THEN** os CT-es de X normalizados a partir daí classificam "SEG. CARGA" como `AD_VALOREM`

### Requirement: Estrutura da tabela de frete
Uma tabela de frete SHALL ter: transportadora, nome, início de vigência, fim de vigência opcional, indicação se os valores já incluem ICMS, fator de cubagem (kg/m³), frete mínimo, taxa de despacho, rotas e áreas especiais. Cada rota MUST ter faixa de CEP de destino, faixa de CEP de origem opcional, faixas de peso com valor, valor por kg excedente, ad valorem, GRIS e pedágio a cada 100 kg.

#### Scenario: Tabela completa aceita
- **WHEN** um `TENANT_ADMIN` envia uma tabela com 3 rotas, faixas de peso 0–5, 5–10 e 10–30 kg, excedente por kg, ad valorem de 0,3% com mínimo, GRIS de 0,2% com mínimo, pedágio por 100 kg e 1 área TDE
- **THEN** a tabela é criada em `DRAFT` com todos esses elementos

### Requirement: Validação da tabela de frete
O sistema MUST rejeitar com 422 uma tabela que tenha CEP diferente de 8 dígitos, faixa com início maior que o fim, faixas de CEP de destino sobrepostas para a mesma origem, faixas de peso que não começam em 0 ou que têm buracos ou sobreposições, valores negativos, percentuais fora de 0–100 ou fator de cubagem menor ou igual a zero.

#### Scenario: Faixas de peso com buraco
- **WHEN** uma rota tem as faixas 0–5 kg e 6–10 kg
- **THEN** a importação é rejeitada com um erro indicando a rota e as faixas sem continuidade

#### Scenario: Faixas de CEP sobrepostas
- **WHEN** duas rotas sem origem cobrem os CEPs de destino 01000000–01999999 e 01500000–02999999
- **THEN** a importação é rejeitada com um erro identificando as duas rotas

### Requirement: Importação por JSON
O sistema SHALL aceitar tabelas de frete no formato JSON canônico publicado no contrato da API, criando uma nova versão em `DRAFT`. Erros de validação MUST ser devolvidos todos juntos, cada um com o caminho do campo.

#### Scenario: JSON com vários erros
- **WHEN** um JSON de tabela tem um CEP inválido e um percentual de 150%
- **THEN** a resposta 422 lista os 2 erros com o caminho de cada campo, e nenhuma tabela é criada

### Requirement: Importação por planilha modelo
O sistema SHALL disponibilizar uma planilha XLSX modelo e aceitar a importação de planilhas nesse formato, criando uma versão em `DRAFT`. Erros de validação MUST indicar aba, linha e coluna.

#### Scenario: Download do modelo
- **WHEN** um usuário do tenant baixa a planilha modelo
- **THEN** recebe um arquivo XLSX com as abas de dados gerais, rotas e faixas, e áreas especiais, com cabeçalhos e uma linha de exemplo em cada aba

#### Scenario: Erro na planilha
- **WHEN** a aba de rotas tem um CEP inválido na linha 12, coluna B
- **THEN** a resposta 422 indica a aba de rotas, a linha 12, a coluna B e o motivo

### Requirement: Ciclo de vida e imutabilidade
Tabelas SHALL ter o status `DRAFT`, `ACTIVE` ou `ARCHIVED`. Só tabelas em `DRAFT` MUST poder ser editadas ou arquivadas. Uma tabela `ACTIVE` é imutável, e uma alteração MUST ser feita criando uma nova versão a partir dela.

#### Scenario: Edição de tabela ativa
- **WHEN** um `TENANT_ADMIN` tenta alterar uma tabela `ACTIVE`
- **THEN** o sistema responde 409 sem mudar nada

#### Scenario: Nova versão a partir da ativa
- **WHEN** um `TENANT_ADMIN` pede uma nova versão de uma tabela `ACTIVE`
- **THEN** o sistema cria uma tabela em `DRAFT` com o mesmo conteúdo e número de versão incrementado

### Requirement: Ativação e vigência
Ao ativar uma versão, o sistema SHALL exigir que o início da vigência seja posterior ao início da última versão ativa da mesma transportadora. Se a versão anterior não tinha fim de vigência ou tinha um fim posterior, o fim MUST passar para o dia anterior ao início da nova. Versões ativas anteriores continuam valendo nos seus períodos.

#### Scenario: Ativação de nova versão
- **WHEN** a versão 1 está ativa desde 2026-01-01 sem fim, e a versão 2 é ativada com início em 2026-11-01
- **THEN** a versão 1 passa a ter fim em 2026-10-31 e a versão 2 vale a partir de 2026-11-01

#### Scenario: Ativação retroativa conflitante
- **WHEN** a última versão ativa começa em 2026-06-01 e se tenta ativar outra com início em 2026-05-01
- **THEN** o sistema responde 409 e nenhuma vigência é alterada

### Requirement: Seleção da tabela vigente
O sistema SHALL selecionar, para um embarque, a tabela `ACTIVE` da transportadora cujo período de vigência contém a data de emissão do CT-e (no fuso America/Sao_Paulo), e nela a rota cuja faixa de CEP de destino contém o CEP de destino. Rotas que também restringem a origem e casam com ela MUST ter prioridade sobre rotas sem origem.

#### Scenario: CT-e emitido antes da troca de tabela
- **WHEN** um CT-e emitido em 2026-10-20 é auditado depois de a versão 2 (início em 2026-11-01) ser ativada
- **THEN** a tabela selecionada é a versão 1

#### Scenario: Sem tabela vigente
- **WHEN** não há tabela ativa da transportadora vigente na data de emissão
- **THEN** a seleção indica ausência de tabela (`NO_TABLE`)

#### Scenario: CEP fora das rotas
- **WHEN** há tabela vigente, mas nenhuma rota cobre o CEP de destino
- **THEN** a seleção indica rota não coberta (`ROUTE_NOT_COVERED`)
