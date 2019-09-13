package org.openl.rules.ruleservice.kafka.publish;

import java.lang.reflect.Method;
import java.util.Objects;

public class Message {
    private Object[] parameters;
    private Exception exception;
    private Method method;
    private byte[] rawData;

    public Message(Method method, Object[] parameters, byte[] bytes) {
        Objects.requireNonNull(method, "method can't be null");
        Objects.requireNonNull(parameters, "methodArgs can't be null");
        this.method = method;
        this.parameters = parameters;
        this.rawData = bytes;
    }

    public Message(Method method, Exception exception, byte[] bytes) {
        Objects.requireNonNull(exception, "exception can't be null");
        this.exception = exception;
        this.rawData = bytes;
    }

    public final Object[] getParameters() throws Exception {
        if (isSuccess()) {
            return parameters.clone();
        } else {
            throw exception;
        }
    }

    public final Method getMethod() throws Exception {
        if (method != null) {
            return method;
        } else {
            throw exception;
        }
    }

    public final boolean isSuccess() {
        return exception == null;
    }

    public final Exception getException() {
        return exception;
    }

    public final byte[] getRawData() {
        return rawData;
    }

    public final String asText() {
        return new String(rawData);
    }
}