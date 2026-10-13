# Spec Delta

## Purpose

Registrar as cotações de frete feitas no checkout (plataforma de e-commerce, hub de cotação ou outra origem) e vinculá-las às NF-es, para que a auditoria compare o valor cobrado no CT-e com o valor cotado na venda.

## ADDED Requirements

### Requirement: Registro de cotação
O sistema SHALL registrar cotações por `POST /api/v1/quotes` (usuário `TENANT_ADMIN`) e por `POST /webhooks/v1/quotes` (assinatura HMAC). Cada cotação tem origem (`VTEX`, `INTELIPOST`, `FRETE_RAPIDO` ou `OTHER`), id externo, CNPJ da transportadora, serviço, valor, data, CEP de destino, peso, valor da carga, prazo, e chave de NF-e e/ou referência de pedido.

#### Scenario: Cotação válida por webhook
- **WHEN** uma integração envia, com assinatura válida, uma cotação de origem `INTELIPOST`, id externo "Q-981", referência de pedido "PED-5521" e valor 39.90
- **THEN** a cotação é registrada no tenant da credencial e a resposta traz o id interno

#### Scenario: Sem chave de NF-e e sem pedido
- **WHEN** uma cotação não traz chave de NF-e nem referência de pedido
- **THEN** o sistema responde 422 indicando que ao menos um dos dois é obrigatório

#### Scenario: Chave de NF-e inválida
- **WHEN** a chave de NF-e informada não tem 44 dígitos ou o dígito verificador está errado
- **THEN** o sistema responde 422 indicando o campo inválido

### Requirement: Idempotência e revisão de cotação
O sistema SHALL identificar a cotação por tenant, origem e id externo. Reenviar o mesmo conteúdo MUST deixar tudo como está. Reenviar com conteúdo diferente MUST criar uma nova revisão, que passa a ser a vigente, e manter a revisão anterior no histórico.

#### Scenario: Reenvio idêntico
- **WHEN** a mesma cotação `INTELIPOST`/"Q-981" é reenviada sem nenhuma mudança
- **THEN** a resposta traz o mesmo id e nenhuma revisão nova é criada

#### Scenario: Reenvio com valor alterado
- **WHEN** a cotação `INTELIPOST`/"Q-981" é reenviada com valor 42.50
- **THEN** é criada a revisão 2 com valor 42.50, que passa a ser a vigente, e a revisão 1 continua consultável

### Requirement: Vínculo de cotação com NF-e
O sistema SHALL vincular a cotação à NF-e do tenant pela chave informada ou, sem chave, pela referência de pedido igual a um `xPed` da NF-e, qualquer que seja a ordem de chegada. Se a referência de pedido casar com mais de uma NF-e, a cotação MUST ficar sem vínculo e marcada como `AMBIGUOUS_ORDER_REFERENCE`.

#### Scenario: Vínculo por chave
- **WHEN** chega uma cotação com a chave da NF-e K e K já está processada
- **THEN** a cotação fica vinculada a K

#### Scenario: Vínculo por pedido com NF-e chegando depois
- **WHEN** uma cotação com o pedido "PED-5521" está registrada e depois é processada uma NF-e com `xPed` "PED-5521"
- **THEN** a cotação passa a estar vinculada a essa NF-e e o vínculo é notificado aos consumidores interessados

#### Scenario: Pedido ambíguo
- **WHEN** a referência "PED-77" casa com duas NF-es do tenant
- **THEN** a cotação fica sem vínculo e marcada como `AMBIGUOUS_ORDER_REFERENCE`

### Requirement: Cotação aplicável a um CT-e
O sistema SHALL considerar como cotação aplicável a uma NF-e a revisão vigente mais recente (pela data da cotação) vinculada a ela cuja raiz de CNPJ da transportadora coincide com a raiz de CNPJ do emitente do CT-e.

#### Scenario: Cotação de outra transportadora
- **WHEN** a NF-e K tem uma cotação da transportadora A e o CT-e que referencia K foi emitido pela transportadora B
- **THEN** não há cotação aplicável para esse CT-e

#### Scenario: Filial da transportadora cotada
- **WHEN** a cotação traz o CNPJ da matriz da transportadora e o CT-e foi emitido por uma filial com a mesma raiz de CNPJ
- **THEN** a cotação é aplicável

### Requirement: Consulta de cotações
O sistema SHALL listar as cotações do tenant em `GET /api/v1/quotes`, com filtros por origem, transportadora, status do vínculo, pedido, chave de NF-e e período, e expor o detalhe com as revisões em `GET /api/v1/quotes/{id}`.

#### Scenario: Filtro por cotações sem vínculo
- **WHEN** o usuário lista as cotações com status de vínculo `UNLINKED`
- **THEN** a resposta traz só as cotações do tenant ainda sem NF-e vinculada
