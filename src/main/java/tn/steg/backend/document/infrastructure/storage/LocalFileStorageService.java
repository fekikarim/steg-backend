package tn.steg.backend.document.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.document.domain.service.FileStorageService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Local filesystem implementation of FileStorageService.
 * Files are persisted outside the application context/webroot in a structured directory tree.
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path rootLocation;

    public LocalFileStorageService(@Value("${steg.storage.local-root-path:./storage/uploads}") String rootPath) {
        this.rootLocation = Paths.get(rootPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootLocation);
            log.info("Local storage directory initialized at: {}", this.rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize local storage root folder: " + rootLocation, e);
        }
    }

    @Override
    public String store(InputStream inputStream, String originalFilename, String mimeType) {
        String cleanFileName = sanitizeFileName(originalFilename);
        String subFolder = UUID.randomUUID().toString();
        Path targetDir = this.rootLocation.resolve(subFolder);

        try {
            Files.createDirectories(targetDir);
            Path destinationFile = targetDir.resolve(cleanFileName).normalize().toAbsolutePath();

            if (!destinationFile.getParent().equals(targetDir.toAbsolutePath())) {
                throw new BusinessRuleException("SECURITY_VIOLATION", "Cannot store file outside current directory (path traversal attempt).");
            }

            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            String storageKey = subFolder + "/" + cleanFileName;
            log.debug("Persisted file {} at key {}", originalFilename, storageKey);
            return storageKey;
        } catch (IOException e) {
            log.error("Failed to store file {}: {}", originalFilename, e.getMessage());
            throw new RuntimeException("Failed to store file " + originalFilename, e);
        }
    }

    @Override
    public InputStream getInputStream(String storageKey) {
        try {
            Path file = this.rootLocation.resolve(storageKey).normalize().toAbsolutePath();
            if (!file.startsWith(this.rootLocation) || !Files.exists(file) || !Files.isReadable(file)) {
                throw new ResourceNotFoundException("Could not read file for key: " + storageKey);
            }
            return new FileInputStream(file.toFile());
        } catch (IOException e) {
            throw new ResourceNotFoundException("Could not read file for key: " + storageKey);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Path file = this.rootLocation.resolve(storageKey).normalize().toAbsolutePath();
            if (file.startsWith(this.rootLocation) && Files.exists(file)) {
                Files.delete(file);
                // Also clean up parent folder if empty
                File parent = file.getParent().toFile();
                if (parent != null && parent.isDirectory() && parent.list() != null && parent.list().length == 0) {
                    parent.delete();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to delete storage key {}: {}", storageKey, e.getMessage());
        }
    }

    private String sanitizeFileName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed_file";
        }
        // Extract plain filename without paths
        String name = Paths.get(filename).getFileName().toString();
        // Replace dangerous characters
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
