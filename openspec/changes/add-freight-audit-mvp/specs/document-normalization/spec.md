# Spec Delta

## Purpose

Converter os XMLs fiscais aceitos no modelo canônico do EasyAudit (contrato estável e independente do layout da SEFAZ), vincular os documentos entre si e aplicar eventos de cancelamento, para que a auditoria e as integrações trabalhem só com dados canônicos.

## ADDED Requirements

### Requirement: Modelo canônico de CT-e
O sistema SHALL converter cada CT-e aceito para o modelo canônico com: chave, número, série, data/hora de emissão, tipo (`NORMAL`, `COMPLEMENT`, `SUBSTITUTE`), transportadora emitente, remetente, destinatário, tomador e seu papel, origem e destino (município IBGE, UF e CEP do destino), valor total da prestação, valor a receber, componentes de valor, ICMS, valor da carga, quantidades e NF-es referenciadas.

#### Scenario: CT-e normal convertido
- **WHEN** um CT-e normal com `vTPrest` 152.37, 2 componentes e 1 NF-e referenciada é processado
- **THEN** a representação canônica tem tipo `NORMAL`, valor total 152.37, os 2 componentes com o nome original e o valor, e a chave da NF-e referenciada

#### Scenario: Campo obrigatório ausente
- **WHEN** o CT-e não tem o grupo de valores da prestação
- **THEN** o documento fica com status `FAILED` e motivo indicando o campo ausente

### Requirement: Modelo canônico de NF-e
O sistema SHALL converter cada NF-e aceita para o modelo canônico com: chave, número, série, data/hora de emissão, emitente, destinatário (com CEP, município e UF), valor total da nota, valor dos produtos, valor do frete, modalidade do frete, CNPJ da transportadora, volumes (quantidade, peso líquido e peso bruto) e referências de pedido (`xPed`).

#### Scenario: NF-e com volumes
- **WHEN** uma NF-e com 2 grupos de volume (pesos brutos 3.500 kg e 1.250 kg) é processada
- **THEN** a representação canônica tem os 2 volumes e o peso bruto total de 4.750 kg

### Requirement: Derivação de pesos e volume do CT-e
O sistema SHALL extrair das quantidades de carga do CT-e o peso real (kg), o peso cubado (kg) e o volume (m³). Toneladas MUST ser convertidas para kg. O tipo de medida MUST ser classificado sem diferenciar maiúsculas de minúsculas nem considerar acentos.

#### Scenario: Peso bruto e cubado informados
- **WHEN** o CT-e informa `infQ` com `cUnid` 01, `tpMed` "PESO BRUTO" e 12.5, e outro `infQ` com `cUnid` 01, `tpMed` "Peso Cubado" e 18.0
- **THEN** o peso real canônico é 12.5 kg e o peso cubado canônico é 18.0 kg

#### Scenario: Peso em toneladas
- **WHEN** o CT-e informa `infQ` com `cUnid` 02 (TON), `tpMed` "PESO BRUTO" e 1.2
- **THEN** o peso real canônico é 1200 kg

#### Scenario: Volume em metros cúbicos
- **WHEN** o CT-e informa `infQ` com `cUnid` 00 (M3) e 0.35
- **THEN** o volume canônico é 0.35 m³

### Requirement: Mapeamento de componentes de valor
O sistema SHALL classificar cada componente de valor do CT-e (nome livre) em um código canônico: `FREIGHT_WEIGHT`, `AD_VALOREM`, `GRIS`, `TOLL`, `TDA`, `TDE`, `DISPATCH_FEE`, `ICMS` ou `OTHER`. A classificação usa um dicionário padrão de sinônimos mais os sinônimos que o tenant cadastrar por transportadora, que prevalecem. Componentes sem correspondência MUST virar `OTHER`, mantendo o nome original.

#### Scenario: Sinônimo padrão
- **WHEN** um componente se chama "FRETE PESO" ou "Frete-Peso"
- **THEN** ele é classificado como `FREIGHT_WEIGHT`

#### Scenario: Sinônimo específico da transportadora
- **WHEN** o tenant cadastrou o sinônimo "TX EMERGENCIAL" como `TDE` para a transportadora X, e um CT-e de X tem um componente com esse nome
- **THEN** o componente é classificado como `TDE`

#### Scenario: Componente desconhecido
- **WHEN** um componente se chama "TAXA XPTO"
- **THEN** ele é classificado como `OTHER`, com o nome original "TAXA XPTO"

### Requirement: Vínculo CT-e ↔ NF-e
O sistema SHALL registrar um vínculo entre o CT-e e cada chave de NF-e referenciada. O vínculo fica pendente até a NF-e ser recebida e MUST ser resolvido quando ela chegar, em qualquer ordem de chegada. A resolução de um vínculo MUST ser notificada aos consumidores interessados.

#### Scenario: NF-e chega antes do CT-e
- **WHEN** a NF-e K já foi processada e chega um CT-e que referencia K
- **THEN** o vínculo é criado já resolvido

#### Scenario: NF-e chega depois do CT-e
- **WHEN** um CT-e que referencia K foi processado e só depois a NF-e K é processada
- **THEN** o vínculo pendente é resolvido e é publicada uma notificação de vínculo resolvido para o CT-e

### Requirement: Vínculo de CT-e complementar e substituto
O sistema SHALL vincular um CT-e complementar ao CT-e que ele complementa, e um CT-e substituto ao CT-e que ele substitui. O CT-e substituído MUST ser marcado como `SUBSTITUTED`.

#### Scenario: CT-e substituto recebido
- **WHEN** chega um CT-e do tipo substituto que referencia o CT-e C1
- **THEN** C1 fica marcado como `SUBSTITUTED` e com vínculo para o substituto

### Requirement: Aplicação de cancelamentos
O sistema SHALL marcar como `CANCELLED` o CT-e ou a NF-e indicada por um evento de cancelamento aceito, inclusive quando o evento chegou antes do documento. O cancelamento MUST ser notificado aos consumidores interessados.

#### Scenario: Cancelamento de CT-e já processado
- **WHEN** chega o evento de cancelamento de um CT-e já processado
- **THEN** o CT-e fica marcado como `CANCELLED` e é publicada uma notificação de cancelamento

#### Scenario: Cancelamento pendente
- **WHEN** um evento de cancelamento foi aceito antes do CT-e correspondente e esse CT-e chega depois
- **THEN** o CT-e já é registrado como `CANCELLED` logo após a normalização

### Requirement: Tolerância a elementos desconhecidos
O sistema MUST ignorar elementos e grupos que não fazem parte do modelo canônico (por exemplo, grupos novos de tributos incluídos por notas técnicas) sem falhar a conversão.

#### Scenario: Grupo novo no XML
- **WHEN** um CT-e 4.00 válido traz um grupo de tributo que o modelo canônico não conhece
- **THEN** a conversão termina com sucesso e os demais campos ficam preenchidos

### Requirement: Publicação de eventos canônicos
O sistema SHALL publicar um evento de integração para cada documento normalizado, cada vínculo resolvido e cada cancelamento aplicado. O payload MUST seguir o contrato publicado e trazer identificador único do evento, tenant, tipo e data. A entrega MUST ser pelo menos uma vez, e os consumidores MUST tolerar duplicatas.

#### Scenario: Evento de documento normalizado
- **WHEN** um CT-e é normalizado com sucesso
- **THEN** é publicado um evento do tipo `document.normalized` com o id do evento, o tenant e a representação canônica

#### Scenario: Entrega duplicada
- **WHEN** um consumidor recebe duas vezes o mesmo evento, com o mesmo id
- **THEN** o efeito no sistema é o mesmo de uma única entrega

### Requirement: Consulta da representação canônica
O sistema SHALL expor a representação canônica de um documento processado em `GET /api/v1/documents/{id}`, incluindo os vínculos (NF-es, CT-e complementado ou substituído) e o status de cancelamento.

#### Scenario: Consulta de CT-e vinculado
- **WHEN** o usuário consulta um CT-e processado que referencia 2 NF-es, uma recebida e outra não
- **THEN** a resposta traz os dados canônicos e os 2 vínculos, um resolvido (com o id da NF-e) e outro pendente
