package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class PredictionNotFoundException extends BusinessException {

    public PredictionNotFoundException() {
        super("Prediction not found", HttpStatus.NOT_FOUND);
    }
}
