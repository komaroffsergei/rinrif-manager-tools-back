package ru.reinform.rinrif.managertools.atr2spec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

final class Atr2SpecIo {
    static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private Atr2SpecIo() {
    }

    static String nowIso() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    static void writeJson(ObjectMapper objectMapper, Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), value);
            move(tempPath, path);
        } catch (IOException error) {
            throw new Atr2SpecException("Failed to write JSON artifact.", 500, error);
        }
    }

    static Map<String, Object> readJson(ObjectMapper objectMapper, Path path) {
        try {
            return objectMapper.readValue(path.toFile(), MAP_TYPE);
        } catch (IOException error) {
            throw new Atr2SpecException("Failed to read JSON artifact.", 500, error);
        }
    }

    static void writeText(Path path, String value) {
        try {
            Files.createDirectories(path.getParent());
            Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
            Files.write(tempPath, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            move(tempPath, path);
        } catch (IOException error) {
            throw new Atr2SpecException("Failed to write text artifact.", 500, error);
        }
    }

    static String readText(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new Atr2SpecException("Failed to read text artifact.", 500, error);
        }
    }

    static Map<String, Object> map() {
        return new LinkedHashMap<String, Object>();
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
