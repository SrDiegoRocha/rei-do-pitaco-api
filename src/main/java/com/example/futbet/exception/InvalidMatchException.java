package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class InvalidMatchException extends BusinessException {

    public InvalidMatchException(String detail) {
        super(detail, HttpStatus.CONFLICT);
    }
}
