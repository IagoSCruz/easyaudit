# Spec Delta

## Purpose

Calcular de forma determinística o frete esperado de um embarque a partir de uma versão de tabela de frete, com detalhamento por componente, para servir de valor de referência na auditoria e na simulação de tabelas.

## ADDED Requirements

> Exemplos desta spec usam a tabela de referência T: fator de cubagem 300 kg/m³; rota com destino 01000000–09999999; faixas 0–5 kg = 20.00, 5–10 kg = 28.00, 10–30 kg = 45.00; excedente 1.20/kg; ad valorem 0,30% (mínimo 2.00); GRIS 0,20% (mínimo 1.50); pedágio 3.10 a cada 100 kg; taxa de despacho 5.00; frete mínimo 40.00; valores sem ICMS.

### Requirement: Entradas do cálculo
O cálculo SHALL receber: versão da tabela, CEP de destino, CEP de origem opcional, peso real (kg), peso cubado opcional (kg), volume opcional (m³), valor da carga e alíquota de ICMS opcional. Peso real negativo ou valor da carga negativo MUST ser rejeitado.

#### Scenario: Entrada inválida
- **WHEN** o cálculo é pedido com peso real -1 kg
- **THEN** o cálculo é rejeitado com erro de validação no campo de peso

### Requirement: Peso taxado
O sistema SHALL usar como peso taxado o maior entre o peso real e o peso cubado. O peso cubado é o informado ou, na falta dele, o volume (m³) multiplicado pelo fator de cubagem da tabela. Sem peso cubado e sem volume, MUST ser usado o peso real.

#### Scenario: Cubado maior que o real
- **WHEN** o peso real é 12.5 kg, o volume é 0.05 m³ e a tabela é T
- **THEN** o peso cubado é 15 kg e o peso taxado é 15 kg

#### Scenario: Peso cubado informado prevalece sobre o volume
- **WHEN** o peso cubado informado é 18 kg e o volume é 0.05 m³
- **THEN** o peso taxado é 18 kg

### Requirement: Frete peso por faixa e excedente
O sistema SHALL calcular o componente `FREIGHT_WEIGHT` pela faixa que contém o peso taxado. Cada faixa cobre pesos maiores que o seu início e até o seu fim (inclusive), e a primeira inclui o zero. Acima da última faixa, o valor MUST ser o da última faixa mais o excedente por kg multiplicado pelos kg acima do fim dela.

#### Scenario: Limite de faixa
- **WHEN** o peso taxado é exatamente 5 kg na tabela T
- **THEN** `FREIGHT_WEIGHT` = 20.00

#### Scenario: Logo acima do limite
- **WHEN** o peso taxado é 5.001 kg na tabela T
- **THEN** `FREIGHT_WEIGHT` = 28.00

#### Scenario: Peso excedente
- **WHEN** o peso taxado é 42 kg na tabela T
- **THEN** `FREIGHT_WEIGHT` = 45.00 + 12 × 1.20 = 59.40

### Requirement: Ad valorem e GRIS
O sistema SHALL calcular `AD_VALOREM` e `GRIS` como o percentual da rota sobre o valor da carga, respeitando o mínimo de cada um.

#### Scenario: Percentual acima do mínimo
- **WHEN** o valor da carga é 1000.00 na tabela T
- **THEN** `AD_VALOREM` = 3.00 e `GRIS` = 2.00

#### Scenario: Mínimo aplicado
- **WHEN** o valor da carga é 50.00 na tabela T
- **THEN** `AD_VALOREM` = 2.00 e `GRIS` = 1.50

### Requirement: Pedágio por fração de 100 kg
O sistema SHALL calcular `TOLL` multiplicando o valor do pedágio pelo número de frações de 100 kg do peso taxado, arredondando a quantidade de frações para cima.

#### Scenario: Peso com fração
- **WHEN** o peso taxado é 230 kg na tabela T
- **THEN** `TOLL` = 3 × 3.10 = 9.30

#### Scenario: Peso pequeno
- **WHEN** o peso taxado é 15 kg na tabela T
- **THEN** `TOLL` = 3.10

### Requirement: Taxas de áreas especiais
Quando o CEP de destino está em uma área especial da tabela, o sistema SHALL somar o componente da área (`TDA` ou `TDE`), calculado como o maior entre o percentual sobre `FREIGHT_WEIGHT` e o valor mínimo da área. Cada tipo de área MUST ser aplicado no máximo uma vez por cálculo.

#### Scenario: Destino em área de TDE
- **WHEN** T tem uma área TDE para 05500000–05599999 com 10% sobre o frete peso e mínimo 15.00, e o embarque vai para 05501000 com `FREIGHT_WEIGHT` 45.00
- **THEN** `TDE` = 15.00

### Requirement: Taxa de despacho e frete mínimo
O sistema SHALL somar a taxa de despacho como `DISPATCH_FEE`. Se a soma dos componentes ficar abaixo do frete mínimo da tabela, o sistema MUST incluir um componente `MINIMUM_ADJUSTMENT` com a diferença.

#### Scenario: Ajuste ao mínimo
- **WHEN** um embarque de 1 kg com carga de 50.00 na tabela T soma 31.60 (20.00 + 2.00 + 1.50 + 3.10 + 5.00)
- **THEN** é incluído `MINIMUM_ADJUSTMENT` = 8.40 e o subtotal é 40.00

### Requirement: ICMS
Quando a tabela não inclui ICMS e uma alíquota maior que zero é informada, o sistema SHALL calcular o total "por dentro" (subtotal ÷ (1 − alíquota)) e registrar a diferença como componente `ICMS`. Se a tabela já inclui ICMS, ou se não houver alíquota, o total MUST ser o subtotal, e o resultado MUST indicar que o ICMS não foi adicionado.

#### Scenario: ICMS por dentro
- **WHEN** um embarque com peso real 12.5 kg, volume 0.05 m³ e carga de 1000.00 na tabela T, com ICMS de 12%, tem subtotal 58.10
- **THEN** o total é 66.02 e `ICMS` = 7.92

#### Scenario: Tabela com ICMS incluso
- **WHEN** a tabela indica que os valores já incluem ICMS e a alíquota informada é 12%
- **THEN** o total é igual ao subtotal e não há componente `ICMS`

### Requirement: Arredondamento e determinismo
O sistema SHALL calcular com precisão decimal exata, arredondar cada componente para 2 casas (meio para cima) e calcular o total como a soma dos componentes arredondados. As mesmas entradas e a mesma versão de tabela MUST sempre dar o mesmo resultado.

#### Scenario: Recálculo idêntico
- **WHEN** o mesmo embarque é calculado duas vezes com a mesma versão de tabela
- **THEN** os dois resultados são idênticos em todos os componentes e no total

### Requirement: Resultado detalhado
O resultado SHALL trazer a tabela e a versão usadas, a rota, a faixa de peso aplicada, os pesos real, cubado e taxado, cada componente com código e valor, o subtotal, o ICMS e o total. Se nenhuma rota cobrir o CEP, o resultado MUST indicar `ROUTE_NOT_COVERED`, sem valores.

#### Scenario: CEP não coberto
- **WHEN** o embarque vai para o CEP 69900000 e nenhuma rota da tabela o cobre
- **THEN** o resultado indica `ROUTE_NOT_COVERED` e não traz componentes

### Requirement: Simulação de tabela
O sistema SHALL permitir que um usuário do tenant simule o cálculo em `POST /api/v1/freight-tables/{id}/simulations` com as entradas do cálculo, para tabelas em `DRAFT` ou `ACTIVE`, sem gravar nenhum efeito.

#### Scenario: Simulação de rascunho
- **WHEN** um `TENANT_ADMIN` simula um embarque em uma tabela em `DRAFT`
- **THEN** o sistema devolve o resultado detalhado, e a tabela e as auditorias não mudam
