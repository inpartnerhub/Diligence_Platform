package com.inpartnerhub.diligence.shared.web;

/** A business rule rejects the request (invalid combination the request DTO cannot express); mapped to 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
