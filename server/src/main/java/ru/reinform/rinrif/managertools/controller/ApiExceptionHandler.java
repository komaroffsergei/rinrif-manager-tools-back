package ru.reinform.rinrif.managertools.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecException;
import ru.reinform.rinrif.managertools.core.AppException;
import ru.reinform.rinrif.managertools.model.ApiModels.AppError;

import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, AppError>> handleAppException(AppException exception) {
        Map<String, AppError> body = new LinkedHashMap<String, AppError>();
        body.put("error", exception.toAppError());
        return ResponseEntity.status(exception.getStatusCode()).body(body);
    }

    @ExceptionHandler(Atr2SpecException.class)
    public ResponseEntity<Map<String, AppError>> handleAtr2SpecException(Atr2SpecException exception) {
        Map<String, AppError> body = new LinkedHashMap<String, AppError>();
        body.put("error", new AppError("ATR2SPEC_ERROR", exception.getMessage(), null));
        return ResponseEntity.status(exception.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, AppError>> handleUnexpected(Exception exception) {
        Map<String, AppError> body = new LinkedHashMap<String, AppError>();
        body.put("error", AppException.toAppError(exception));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
