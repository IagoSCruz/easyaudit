package br.com.easyaudit.documents.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de armazenamento do XML original. O adapter atual grava no PostgreSQL; trocar por um armazenamento de
 * objetos (S3) não muda os casos de uso.
 */
public interface RawDocumentStorage {

    /** Grava os bytes originais do documento do tenant corrente, sem alteração. */
    void store(UUID documentId, byte[] content);

    Optional<byte[]> load(UUID documentId);
}
