package ru.reinform.rinrif.managertools.core;

import ru.reinform.rinrif.managertools.model.ApiModels.AppError;

public class AppException extends RuntimeException {
    private final String code;
    private final int statusCode;
    private final String details;

    public AppException(String code, String message, int statusCode) {
        this(code, message, statusCode, null);
    }

    public AppException(String code, String message, int statusCode, String details) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getDetails() {
        return details;
    }

    public AppError toAppError() {
        return new AppError(code, getMessage(), details);
    }

    public static AppError toAppError(Throwable error) {
        if (error instanceof AppException) {
            return ((AppException) error).toAppError();
        }
        return new AppError("INTERNAL_ERROR", "Internal server error.", error.getMessage());
    }
}
