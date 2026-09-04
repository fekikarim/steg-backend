package tn.steg.backend.document.domain.service;

import java.io.InputStream;

/**
 * Storage abstraction port for managing binary file objects.
 * Implementations may target local disk, S3-compatible object stores, etc.
 */
public interface FileStorageService {

    /**
     * Stores the given stream into the storage backend.
     *
     * @param inputStream      binary stream
     * @param originalFilename original client file name
     * @param mimeType         detected MIME type
     * @return storage key identifying the persisted file
     */
    String store(InputStream inputStream, String originalFilename, String mimeType);

    /**
     * Loads the file as an InputStream.
     *
     * @param storageKey storage identifier
     * @return InputStream of the file content
     */
    InputStream getInputStream(String storageKey);

    /**
     * Deletes the file identified by storageKey.
     *
     * @param storageKey storage identifier
     */
    void delete(String storageKey);
}
