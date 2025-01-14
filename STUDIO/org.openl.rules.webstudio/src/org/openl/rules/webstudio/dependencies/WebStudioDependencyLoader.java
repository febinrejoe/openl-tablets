package org.openl.rules.webstudio.dependencies;

import java.util.Collection;
import java.util.LinkedHashSet;

import org.openl.CompiledOpenClass;
import org.openl.classloader.OpenLBundleClassLoader;
import org.openl.dependency.CompiledDependency;
import org.openl.exception.OpenLCompilationException;
import org.openl.message.OpenLMessage;
import org.openl.message.OpenLMessagesUtils;
import org.openl.message.Severity;
import org.openl.rules.project.instantiation.AbstractDependencyManager;
import org.openl.rules.project.instantiation.SimpleDependencyLoader;
import org.openl.rules.project.model.Module;
import org.openl.syntax.exception.SyntaxNodeException;
import org.openl.types.NullOpenClass;

final class WebStudioDependencyLoader extends SimpleDependencyLoader {

    WebStudioDependencyLoader(String dependencyName,
            Collection<Module> modules,
            boolean singleModuleMode,
            boolean projectDependency) {
        super(dependencyName, modules, singleModuleMode, false, projectDependency);
    }

    @Override
    protected ClassLoader buildClassLoader(AbstractDependencyManager dependencyManager) {
        ClassLoader projectClassLoader = dependencyManager.getClassLoader(modules.iterator().next().getProject());
        OpenLBundleClassLoader simpleBundleClassLoader = new OpenLBundleClassLoader(null);
        simpleBundleClassLoader.addClassLoader(projectClassLoader);
        return simpleBundleClassLoader;
    }

    @Override
    protected CompiledDependency onCompilationFailure(Exception ex,
            AbstractDependencyManager dependencyManager) throws OpenLCompilationException {
        ClassLoader classLoader = dependencyManager.getClassLoader(getModules().iterator().next().getProject());
        return createFailedCompiledDependency(getDependencyName(), classLoader, ex);
    }

    private CompiledDependency createFailedCompiledDependency(String dependencyName,
            ClassLoader classLoader,
            Exception ex) {
        Collection<OpenLMessage> messages = new LinkedHashSet<>();
        for (OpenLMessage openLMessage : OpenLMessagesUtils.newErrorMessages(ex)) {
            String message = String
                .format("Failed to load dependent module '%s': %s", dependencyName, openLMessage.getSummary());
            messages.add(new OpenLMessage(message, Severity.ERROR));
        }

        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);

        try {
            return new CompiledDependency(dependencyName,
                new CompiledOpenClass(NullOpenClass.the,
                    messages,
                    new SyntaxNodeException[0],
                    new SyntaxNodeException[0]));
        } finally {
            Thread.currentThread().setContextClassLoader(oldClassLoader);
        }
    }
}
