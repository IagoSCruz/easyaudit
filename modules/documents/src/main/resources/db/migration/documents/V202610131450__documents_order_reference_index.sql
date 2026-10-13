-- Busca de NF-es por referência de pedido (xPed) para o vínculo de cotações.
CREATE INDEX document_order_references_idx ON documents.document USING gin ((canonical -> 'orderReferences'))
    WHERE kind = 'NFE';
