package org.openl.rules.ruleservice.activiti.beans;

import org.activiti.engine.delegate.DelegateExecution;
import org.openl.rules.activiti.spring.result.ResultValue;
import org.openl.rules.activiti.util.IRulesRuntimeContextUtils;
import org.openl.rules.context.IRulesRuntimeContext;
import org.openl.rules.ruleservice.core.OpenLService;
import org.openl.rules.ruleservice.publish.RuleServiceManager;
import org.springframework.beans.factory.annotation.Autowired;

public class OpenLEngine {
    private RuleServiceManager ruleServiceManager;

    @Autowired
    public void setRuleServiceManager(RuleServiceManager ruleServiceManager) {
        this.ruleServiceManager = ruleServiceManager;
    }

    public IRulesRuntimeContext buildRuntimeContext(DelegateExecution execution) {
        return IRulesRuntimeContextUtils.buildRuntimeContext(execution);
    }

    public ResultValue execute(String serviceName, String methodName, Object... args) throws Exception {
        OpenLService openLService = ruleServiceManager.getServiceByName(serviceName);
        if (openLService == null) {
            throw new OpenLServiceNotFoundException(
                String.format("OpenL service '%s' hasn't been found!", serviceName));
        } else {
            Object serviceBean = openLService.getServiceBean();
            Class<?> serviceClass = openLService.getServiceClass();
            Object result = org.openl.rules.activiti.spring.OpenLEngine
                .findAndInvokeMethod(methodName, serviceBean, serviceClass, args);
            return new ResultValue(result);
        }
    }

}
