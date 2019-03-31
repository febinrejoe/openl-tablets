package org.openl.rules.project.instantiation;

import java.io.File;
import java.net.URL;

import org.openl.dependency.IDependencyManager;
import org.openl.rules.extension.instantiation.ExtensionDescriptorFactory;
import org.openl.rules.extension.instantiation.IExtensionDescriptor;
import org.openl.rules.project.model.MethodFilter;
import org.openl.rules.project.model.Module;
import org.openl.rules.runtime.InterfaceClassGeneratorImpl;
import org.openl.rules.runtime.RulesEngineFactory;
import org.openl.source.IOpenSourceCodeModule;
import org.openl.source.impl.ModuleFileSourceCodeModule;
import org.openl.source.impl.URLSourceCodeModule;
import org.openl.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The simplest {@link RulesInstantiationStrategyFactory} for module that
 * contains only Excel file.
 *
 * @author PUdalau
 */
public class ApiBasedInstantiationStrategy extends SingleModuleInstantiationStrategy {
    private final Logger log = LoggerFactory.getLogger(ApiBasedInstantiationStrategy.class);

    /**
     * Rules engine factory for module that contains only Excel file.
     */
    private RulesEngineFactory<?> engineFactory;

    public ApiBasedInstantiationStrategy(Module module, boolean executionMode, IDependencyManager dependencyManager) {
        super(module, executionMode, dependencyManager);
    }

    public ApiBasedInstantiationStrategy(Module module,
                                         boolean executionMode,
                                         IDependencyManager dependencyManager,
                                         ClassLoader classLoader) {
        super(module, executionMode, dependencyManager, classLoader);
    }

    @Override
    public void reset() {
        super.reset();
        if (engineFactory != null) {
            getEngineFactory().reset(false);
        }
    }

    @Override
    public void forcedReset() {
        super.forcedReset();
        engineFactory = null;
    }

    @Override
    public Class<?> getGeneratedRulesClass() throws RulesInstantiationException {
        // Service class for current implementation will be class, generated at
        // runtime by factory.

        // Using project class loader for interface generation.
        //
        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClassLoader());
        try {
            return getEngineFactory().getInterfaceClass();
        } catch (Exception e) {
            throw new RulesInstantiationException("Failed to resolve a interface.", e);
        } finally {
            Thread.currentThread().setContextClassLoader(oldClassLoader);
        }
    }

    private IOpenSourceCodeModule getSourceCode(Module module) {
        File sourceFile = new File(getModule().getRulesRootPath().getPath());
        URL url = URLSourceCodeModule.toUrl(sourceFile);
        return new ModuleFileSourceCodeModule(url, getModule().getName());
    }
 
    @SuppressWarnings("unchecked")
    protected RulesEngineFactory<?> getEngineFactory() {
        Class<Object> serviceClass;
        try {
            serviceClass = (Class<Object>) getServiceClass();
        } catch (ClassNotFoundException e) {
            log.debug("Failed to get service class.", e);
            serviceClass = null;
        }
        if (engineFactory == null || (serviceClass != null && !engineFactory.getInterfaceClass().equals(serviceClass))) {
            if (getModule().getExtension() != null) {
                IExtensionDescriptor extensionDescriptor = ExtensionDescriptorFactory.getExtensionDescriptor(getModule().getExtension(),
                        getClassLoader());
    
                IOpenSourceCodeModule source = extensionDescriptor.getSourceCode(getModule());
                source.setParams(prepareExternalParameters());
    
                String openlName = extensionDescriptor.getOpenLName();
                engineFactory = new RulesEngineFactory<>(openlName, source, serviceClass);
            } else {
                IOpenSourceCodeModule source = getSourceCode(getModule());
                source.setParams(prepareExternalParameters());

                engineFactory = new RulesEngineFactory<>(source, serviceClass);
            }

            // Information for interface generation, if generation required.
            Module m = getModule();
            MethodFilter methodFilter = m.getMethodFilter();
            if (methodFilter != null && (CollectionUtils.isNotEmpty(methodFilter.getExcludes()) || CollectionUtils.isNotEmpty(methodFilter.getIncludes()))) {
                String[] includes = new String[]{};
                String[] excludes = new String[]{};
                includes = methodFilter.getIncludes().toArray(includes);
                excludes = methodFilter.getExcludes().toArray(excludes);
                engineFactory.setInterfaceClassGenerator(new InterfaceClassGeneratorImpl(includes, excludes));
            }

            engineFactory.setExecutionMode(isExecutionMode());
            engineFactory.setDependencyManager(getDependencyManager());
        }
        return engineFactory;
    }

    @Override
    public Object instantiate(Class<?> rulesClass) throws RulesInstantiationException {

        // Ensure that instantiation will be done in strategy classLoader.
        //
        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClassLoader());
        try {
            return getEngineFactory().newEngineInstance();
        } catch (Exception e) {
            throw new RulesInstantiationException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(oldClassLoader);
        }
    }

}
