package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class ZoneNotFoundException extends BusinessException {

    public ZoneNotFoundException() {
        super("Zone not found", HttpStatus.NOT_FOUND);
    }
}
