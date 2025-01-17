package org.openl.rules.ruleservice.publish.jaxrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;

import javax.ws.rs.core.Response;

import org.junit.Test;

public class JAXRSInvocationHandlerTest {

    @Test
    public void checkNotNullConstructorArguments() {
        new JAXRSInvocationHandler(new Object(), new HashMap<Method, Method>());
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkNullTargetConstructorArgument() {
        new JAXRSInvocationHandler(null, new HashMap<Method, Method>());
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkNullMethodsConstructorArgumen() {
        new JAXRSInvocationHandler(new Object(), null);
    }

    @Test(expected = IllegalStateException.class)
    public void checkInvokeOnUnknownMethod() throws Throwable {
        Object target = new Object();
        HashMap<Method, Method> methods = new HashMap<>();
        JAXRSInvocationHandler handler = new JAXRSInvocationHandler(target, methods);
        Method unknownMethod = Object.class.getDeclaredMethod("hashCode");
        handler.invoke(null, unknownMethod, null);
    }

    @Test
    public void checkNullArguments() throws Throwable {
        InvokedClass target = mock(InvokedClass.class);
        when(target.doWork()).thenReturn("Done");
        HashMap<Method, Method> methods = new HashMap<>();
        Method method = target.getClass().getDeclaredMethod("doWork");
        methods.put(method, method);

        JAXRSInvocationHandler handler = new JAXRSInvocationHandler(target, methods);
        Object result = handler.invoke(null, method, null);

        assertTrue(result instanceof Response);
        assertEquals("Done", ((Response) result).getEntity());
    }

    private interface InvokedClass {
        String doWork();
    }
}
