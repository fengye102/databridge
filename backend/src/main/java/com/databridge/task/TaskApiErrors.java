package com.databridge.task;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.databridge")
public class TaskApiErrors {
    @ExceptionHandler(EmptyResultDataAccessException.class)
    ResponseEntity<ApiError> notFound() {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Task or report does not exist");
    }

    @ExceptionHandler(TaskRepository.TargetAlreadyOwnedException.class)
    ResponseEntity<ApiError> targetAlreadyOwned() {
        return error(HttpStatus.CONFLICT, "TARGET_ALREADY_OWNED", "Target is already assigned");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> badRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid task request");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> conflict() {
        return error(HttpStatus.CONFLICT, "INVALID_STATE", "Task state does not allow this operation");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internalError() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Task operation failed");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }

    record ApiError(String code, String message) { }
}
