package org.openl.binding.impl.method;

import org.openl.binding.MethodUtil;
import org.openl.types.IMemberMetaInfo;
import org.openl.types.IMethodSignature;
import org.openl.types.IOpenClass;
import org.openl.types.IOpenMethod;

public abstract class AOpenMethodDelegator implements IOpenMethod, IMethodSignature {
    private IOpenMethod delegate;

    public AOpenMethodDelegator(IOpenMethod delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException();
        }
        this.delegate = delegate;
    }

    public IOpenMethod getDelegate() {
        return delegate;
    }

    @Override
    public IOpenClass getType() {
        return getDelegate().getType();
    }

    @Override
    public IOpenClass getDeclaringClass() {
        return getDelegate().getDeclaringClass();
    }

    @Override
    public String getDisplayName(int mode) {
        return MethodUtil.printSignature(this, mode);
    }

    @Override
    public IMemberMetaInfo getInfo() {
        return null;
    }

    @Override
    public IOpenMethod getMethod() {
        return this;
    }

    @Override
    public String getName() {
        return getDelegate().getName();
    }

    @Override
    public int getNumberOfParameters() {
        return getDelegate().getSignature().getNumberOfParameters();
    }

    @Override
    public String getParameterName(int i) {
        return getDelegate().getSignature().getParameterName(i);
    }

    @Override
    public IOpenClass getParameterType(int i) {
        return getDelegate().getSignature().getParameterType(i);
    }

    @Override
    public IOpenClass[] getParameterTypes() {
        return getDelegate().getSignature().getParameterTypes();
    }

    @Override
    public IMethodSignature getSignature() {
        return this;
    }

    @Override
    public boolean isStatic() {
        return getDelegate().isStatic();
    }

    @Override
    public boolean isConstructor() {
        return false;
    }

    @Override
    public String toString() {
        return getName();
    }

}
