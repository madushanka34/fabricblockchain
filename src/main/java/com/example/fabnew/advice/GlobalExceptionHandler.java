package com.example.fabnew.advice;

import io.grpc.StatusRuntimeException;
import org.hyperledger.fabric.client.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<String> handleGatewayException(GatewayException e) {
        String errorDetails = "Fabric Gateway Error: " + e.getMessage();
        if (e.getCause() instanceof StatusRuntimeException) {
            StatusRuntimeException sre = (StatusRuntimeException) e.getCause();
            errorDetails += " (gRPC Status: " + sre.getStatus().getCode() + ")";
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorDetails);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGeneralException(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Internal Server Error: " + e.getMessage());
    }
}
