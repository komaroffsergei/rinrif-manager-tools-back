package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

class JsonFileStore<T> {
    private final Path filePath;
    private final T defaultValue;
    private final TypeReference<T> typeReference;
    private final ObjectMapper objectMapper;

    JsonFileStore(Path filePath, T defaultValue, TypeReference<T> typeReference, ObjectMapper objectMapper) {
        this.filePath = filePath;
        this.defaultValue = defaultValue;
        this.typeReference = typeReference;
        this.objectMapper = objectMapper;
    }

    boolean exists() {
        return Files.exists(filePath);
    }

    T read() {
        if (!Files.exists(filePath)) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(filePath.toFile(), typeReference);
        } catch (IOException error) {
            throw new AppException("INTERNAL_ERROR", "Failed to read storage file.", 500, error.getMessage());
        }
    }

    void write(T value) {
        try {
            Files.createDirectories(filePath.getParent());
            Path tempPath = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), value);
            try {
                Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new AppException("INTERNAL_ERROR", "Failed to write storage file.", 500, error.getMessage());
        }
    }
}
