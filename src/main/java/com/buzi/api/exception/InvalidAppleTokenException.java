package com.buzi.api.exception;

public class InvalidAppleTokenException extends RuntimeException {
    public InvalidAppleTokenException(String message){
        super(message);
    }

    public InvalidAppleTokenException(String message, Throwable cause){
        super(message, cause);
    }
}
