package org.openl.binding.impl;

import java.lang.reflect.Method;

import org.openl.binding.IBindingContext;
import org.openl.binding.IBoundNode;
import org.openl.binding.MethodUtil;
import org.openl.binding.exception.MethodNotFoundException;
import org.openl.binding.impl.cast.AutoCastFactory;
import org.openl.binding.impl.cast.AutoCastReturnType;
import org.openl.binding.impl.method.MethodSearch;
import org.openl.binding.impl.method.VarArgsOpenMethod;
import org.openl.syntax.ISyntaxNode;
import org.openl.syntax.impl.ISyntaxConstants;
import org.openl.syntax.impl.IdentifierNode;
import org.openl.types.IMethodCaller;
import org.openl.types.IOpenClass;
import org.openl.types.IOpenField;
import org.openl.types.impl.CastingMethodCaller;
import org.openl.types.java.JavaOpenMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author snshor, Yury Molchan
 */
public class MethodNodeBinder extends ANodeBinder {

    private final Logger log = LoggerFactory.getLogger(MethodNodeBinder.class);

    private IMethodCaller autoCastReturnTypeWrap(IBindingContext bindingContext,
            IMethodCaller methodCaller,
            IOpenClass[] parameterTypes) {

        JavaOpenMethod method = null;

        if (methodCaller instanceof CastingMethodCaller) {
            CastingMethodCaller castingMethodCaller = (CastingMethodCaller) methodCaller;
            if (castingMethodCaller.getMethod() instanceof JavaOpenMethod) {
                method = (JavaOpenMethod) castingMethodCaller.getMethod();
            }
        }

        if (methodCaller instanceof VarArgsOpenMethod) {
            VarArgsOpenMethod varArgsOpenMethod = (VarArgsOpenMethod) methodCaller;
            if (varArgsOpenMethod.getDelegate() instanceof JavaOpenMethod) {
                method = (JavaOpenMethod) varArgsOpenMethod.getDelegate();
            }
        }

        if (methodCaller instanceof JavaOpenMethod) {
            method = (JavaOpenMethod) methodCaller;
        }

        if (method instanceof JavaOpenMethod) {
            Method javaMethod = method.getJavaMethod();
            AutoCastReturnType autoCastReturnType = javaMethod.getAnnotation(AutoCastReturnType.class);
            if (autoCastReturnType != null) {
                Class<? extends AutoCastFactory> clazz = autoCastReturnType.value();
                try {
                    AutoCastFactory autoCastFactory = clazz.newInstance();
                    return autoCastFactory.build(bindingContext, methodCaller, method, parameterTypes);
                } catch (InstantiationException | IllegalAccessException e) {
                    return method;
                }
            }
        }

        return methodCaller;
    }

    @Override
    public IBoundNode bind(ISyntaxNode node, IBindingContext bindingContext) throws Exception {

        IBoundNode errorNode = validateNode(node, bindingContext);
        if (errorNode != null) {
            return errorNode;
        }

        int childrenCount = node.getNumberOfChildren();

        ISyntaxNode lastNode = node.getChild(childrenCount - 1);

        String methodName = ((IdentifierNode) lastNode).getIdentifier();

        IBoundNode[] children = bindChildren(node, bindingContext, 0, childrenCount - 1);
        if (hasErrorBoundNode(children)) {
            return new ErrorBoundNode(node);
        }
        IOpenClass[] parameterTypes = getTypes(children);

        IMethodCaller methodCaller = bindingContext
            .findMethodCaller(ISyntaxConstants.THIS_NAMESPACE, methodName, parameterTypes);

        BindHelper.checkOnDeprecation(node, bindingContext, methodCaller);
        methodCaller = autoCastReturnTypeWrap(bindingContext, methodCaller, parameterTypes);

        if (methodCaller != null) {
            log(methodName, parameterTypes, "entirely appropriate by signature method");
            return new MethodBoundNode(node, children, methodCaller);
        }

        // can`t find directly the method with given name and parameters. so,
        // if there are any parameters, try to bind it some additional ways
        // someMethod( parameter1, ... )
        //
        if (childrenCount > 1) {
            return bindWithAdditionalBinders(node, bindingContext, methodName, parameterTypes, children, childrenCount);
        }

        // There are no other variants - so error.
        throw new MethodNotFoundException(methodName, parameterTypes);
    }

    protected IBoundNode makeArrayParametersMethod(ISyntaxNode methodNode,
            IBindingContext bindingContext,
            String methodName,
            IOpenClass[] argumentTypes,
            IBoundNode[] children) throws Exception {
        return new ArrayArgumentsMethodBinder(methodName, argumentTypes, children).bind(methodNode, bindingContext);
    }

    protected IBoundNode bindWithAdditionalBinders(ISyntaxNode methodNode,
            IBindingContext bindingContext,
            String methodName,
            IOpenClass[] argumentTypes,
            IBoundNode[] children,
            int childrenCount) throws Exception {

        // Try to bind method, that contains one of the arguments as array type.
        // For this try to find method without
        // array argument (but the component type of it on the same place). And
        // call it several times on runtime
        // for collecting results.
        //
        IBoundNode arrayParametersMethod = makeArrayParametersMethod(methodNode,
            bindingContext,
            methodName,
            argumentTypes,
            children);

        if (arrayParametersMethod != null) {
            log(methodName, argumentTypes, "array argument method");
            return arrayParametersMethod;
        }

        // Get the root component type and dimension of the array.
        IOpenClass argumentType = argumentTypes[0];
        int dims = 0;
        while (argumentType.isArray()) {
            dims++;
            argumentType = argumentType.getComponentClass();
        }

        // Try to bind method call Name(driver) as driver.Name;
        //
        if (childrenCount == 2) {

            // only one child, as there are 2 nodes, one of them is the function itself.
            //
            IOpenField field = bindingContext.findFieldFor(argumentType, methodName, false);
            if (field != null) {
                log(methodName, argumentTypes, "field access method");
                return new FieldBoundNode(methodNode, field, children[0], dims);
            }
        }

        throw new MethodNotFoundException(methodName, argumentTypes);
    }

    private void log(String methodName, IOpenClass[] argumentTypes, String bindingType) {
        if (log.isTraceEnabled()) {
            String method = MethodUtil.printMethod(methodName, argumentTypes);
            log.trace("Method {} was binded as {}", method, bindingType);
        }
    }

    @Override
    public IBoundNode bindTarget(ISyntaxNode node, IBindingContext bindingContext, IBoundNode target) throws Exception {

        IBoundNode errorNode = validateNode(node, bindingContext);
        if (errorNode != null) {
            return errorNode;
        }

        int childrenCount = node.getNumberOfChildren();
        ISyntaxNode lastNode = node.getChild(childrenCount - 1);

        String methodName = ((IdentifierNode) lastNode).getIdentifier();

        IBoundNode[] children = bindChildren(node, bindingContext, 0, childrenCount - 1);
        IOpenClass[] types = getTypes(children);

        IOpenClass type = target.getType();
        IMethodCaller methodCaller = MethodSearch.findMethod(methodName, types, bindingContext, type);
        BindHelper.checkOnDeprecation(node, bindingContext, methodCaller);

        if (methodCaller == null) {
            throw new MethodNotFoundException(methodName, types);
        }

        errorNode = validateMethod(node, bindingContext, target, methodCaller);
        if (errorNode != null) {
            return errorNode;
        }
        return new MethodBoundNode(node, children, methodCaller, target);
    }

    private IBoundNode validateMethod(ISyntaxNode node,
            IBindingContext bindingContext,
            IBoundNode target,
            IMethodCaller methodCaller) {
        boolean methodIsStatic = methodCaller.getMethod().isStatic();
        if (target.isStaticTarget() != methodIsStatic) {
            if (methodIsStatic) {
                BindHelper.processWarn("Access of a static method from non-static object", node, bindingContext);
            } else {
                return makeErrorNode("Access of a non-static method from a static object", node, bindingContext);
            }
        }
        return null;
    }

    private IBoundNode validateNode(ISyntaxNode node, IBindingContext bindingContext) {
        if (node.getNumberOfChildren() < 1) {
            return makeErrorNode("New node should have at least one subnode", node, bindingContext);
        }
        return null;
    }
}
