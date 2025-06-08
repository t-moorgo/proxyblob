package com.proxyblob.transport.exception;

import lombok.experimental.StandardException;

import java.io.IOException;

@StandardException
public abstract class TransportException extends IOException {
}
