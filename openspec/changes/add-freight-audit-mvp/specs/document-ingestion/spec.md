# Spec Delta

## Purpose

Receber os XMLs fiscais (CT-e, NF-e e seus eventos de cancelamento) enviados pelo lojista ou por integrações, aceitar só documentos válidos, autorizados e pertencentes ao tenant, guardar o original sem alteração e encaminhá-lo para processamento.

## ADDED Requirements

### Requirement: Tipos de documento suportados
O sistema SHALL aceitar apenas: CT-e modelo 57 processado (`cteProc`) no layout 4.00, NF-e modelo 55 processada (`nfeProc`) no layout 4.00, e eventos de cancelamento processados de CT-e e de NF-e (`procEventoCTe`/`procEventoNFe` com `tpEvento` 110111). Qualquer outro tipo ou versão MUST ser rejeitado com o motivo `UNSUPPORTED_DOCUMENT`.

#### Scenario: CT-e 4.00 processado
- **WHEN** o tenant envia um `cteProc` versão 4.00 autorizado
- **THEN** o documento é aceito com o tipo `CTE`

#### Scenario: Versão não suportada
- **WHEN** o tenant envia um CT-e no layout 3.00
- **THEN** o documento é rejeitado com o motivo `UNSUPPORTED_DOCUMENT`

#### Scenario: Evento diferente de cancelamento
- **WHEN** o tenant envia um evento de Carta de Correção de CT-e
- **THEN** o documento é rejeitado com o motivo `UNSUPPORTED_DOCUMENT`

### Requirement: Upload de documentos
O sistema SHALL receber, via `POST /api/v1/documents` em multipart, um ou mais arquivos XML (até 1 MB cada) ou arquivos ZIP (até 20 MB e 500 entradas) com XMLs. A resposta MUST listar o resultado de cada XML, com nome do arquivo, status (`ACCEPTED`, `DUPLICATE` ou `REJECTED`), id do documento quando houver e motivo da rejeição.

#### Scenario: Upload misto
- **WHEN** o tenant envia um ZIP com 3 CT-es válidos, 1 arquivo corrompido e 1 CT-e já recebido antes
- **THEN** a resposta lista 3 itens `ACCEPTED`, 1 `REJECTED` com motivo `MALFORMED_XML` e 1 `DUPLICATE` com o id do documento existente

#### Scenario: Arquivo acima do limite
- **WHEN** o tenant envia um XML com mais de 1 MB
- **THEN** o item é rejeitado com o motivo `FILE_TOO_LARGE` e os demais arquivos da requisição continuam sendo processados

#### Scenario: Papel sem permissão de envio
- **WHEN** um usuário que tem apenas o papel `VIEWER` envia documentos
- **THEN** o sistema responde 403

### Requirement: Recebimento de documentos por webhook
O sistema SHALL receber documentos em `POST /webhooks/v1/documents`, autenticado por assinatura HMAC, com um envelope JSON de até 100 XMLs codificados em base64. Cada XML MUST passar pelas mesmas validações do upload, e a resposta MUST ter o mesmo formato de resultado por item.

#### Scenario: Webhook com documentos válidos
- **WHEN** uma integração envia, com assinatura válida, um envelope com 2 NF-es autorizadas do tenant
- **THEN** a resposta lista 2 itens `ACCEPTED` vinculados ao tenant da credencial

#### Scenario: Envelope acima do limite
- **WHEN** o envelope tem mais de 100 documentos
- **THEN** o sistema responde 413 e nenhum documento é aceito

### Requirement: Validação de autorização e de assinatura
O sistema MUST rejeitar com `NOT_AUTHORIZED` documentos sem protocolo de autorização (`cStat` 100 ou 150) e eventos sem protocolo de registro (`cStat` 135, 136 ou 155). Também MUST rejeitar com `INVALID_SIGNATURE` documentos cuja assinatura digital XML não confere com o conteúdo assinado.

#### Scenario: CT-e sem protocolo
- **WHEN** o tenant envia um XML `CTe` assinado, mas sem o grupo de protocolo
- **THEN** o documento é rejeitado com o motivo `NOT_AUTHORIZED`

#### Scenario: Conteúdo adulterado
- **WHEN** o valor `vTPrest` de um CT-e assinado foi alterado depois da assinatura
- **THEN** o documento é rejeitado com o motivo `INVALID_SIGNATURE`

### Requirement: Rejeição de documentos de homologação
O sistema MUST rejeitar com o motivo `HOMOLOGATION_DOCUMENT` documentos emitidos em ambiente de homologação (`tpAmb` = 2), exceto quando a instalação estiver configurada explicitamente para aceitá-los.

#### Scenario: Documento de homologação em produção
- **WHEN** um CT-e com `tpAmb` = 2 é enviado em uma instalação sem a aceitação de homologação habilitada
- **THEN** o documento é rejeitado com o motivo `HOMOLOGATION_DOCUMENT`

### Requirement: Posse do documento pelo tenant
O sistema MUST aceitar um CT-e apenas quando o CNPJ do tomador do serviço pertence ao tenant, e uma NF-e apenas quando o CNPJ do emitente ou do destinatário pertence ao tenant. A posse considera CNPJs completos e raízes de CNPJ cadastradas. Documentos de outra empresa MUST ser rejeitados com `NOT_TENANT_DOCUMENT`.

#### Scenario: CT-e com tomador do tenant
- **WHEN** o tomador do CT-e (indicado por `toma3` ou `toma4`) tem um CNPJ cuja raiz está cadastrada no tenant
- **THEN** o documento é aceito

#### Scenario: CT-e com tomador de outra empresa
- **WHEN** o tomador do CT-e é uma empresa que não pertence ao tenant
- **THEN** o documento é rejeitado com o motivo `NOT_TENANT_DOCUMENT`

#### Scenario: Evento de cancelamento recebido antes do documento
- **WHEN** chega o evento de cancelamento de um CT-e que o tenant ainda não enviou
- **THEN** o evento é aceito e fica pendente, e só é aplicado se um CT-e com aquela chave for aceito para o tenant

### Requirement: Idempotência pela chave de acesso
O sistema SHALL identificar cada documento pela chave de acesso de 44 posições (e cada evento por chave, tipo e sequência) dentro do tenant. O reenvio de um documento já recebido MUST retornar `DUPLICATE` com o id existente, sem reprocessar nem alterar o original.

#### Scenario: Reenvio do mesmo CT-e
- **WHEN** um CT-e com uma chave que já existe no tenant é reenviado pelo webhook
- **THEN** o item retorna `DUPLICATE` com o id do documento original e nenhum novo processamento é disparado

#### Scenario: Mesma chave em tenants diferentes
- **WHEN** a mesma NF-e é enviada pelo tenant emitente e pelo tenant destinatário
- **THEN** cada tenant tem o seu próprio registro do documento

### Requirement: Guarda do XML original
O sistema SHALL guardar os bytes originais de cada documento aceito, sem nenhuma alteração, e disponibilizá-los em `GET /api/v1/documents/{id}/xml`. O sistema MUST NOT apagar documentos aceitos.

#### Scenario: Download do original
- **WHEN** o usuário baixa o XML de um documento aceito
- **THEN** os bytes retornados são idênticos aos recebidos, com o mesmo hash SHA-256 registrado na ingestão

### Requirement: Processamento assíncrono e consulta de status
O sistema SHALL processar os documentos aceitos de forma assíncrona e expor o status de cada um (`RECEIVED`, `PROCESSED`, `FAILED`) em `GET /api/v1/documents` (com filtros por tipo, status, chave e período de recebimento) e em `GET /api/v1/documents/{id}`. Uma falha MUST registrar o motivo.

#### Scenario: Documento processado
- **WHEN** um CT-e aceito termina a normalização
- **THEN** o status passa a `PROCESSED` e a consulta mostra a data do processamento

#### Scenario: Falha persistente
- **WHEN** o processamento de um documento falha mesmo depois de todas as novas tentativas
- **THEN** o status passa a `FAILED`, com o motivo registrado, e o documento continua disponível para download
