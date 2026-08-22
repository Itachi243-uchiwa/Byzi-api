package com.byzi.api.exception;

public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException(String message) {
        super(message);
    }
}