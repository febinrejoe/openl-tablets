package org.openl.rules.ruleservice.rest;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Resource;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.openl.rules.ruleservice.publish.RuleServiceManager;

@Produces(MediaType.APPLICATION_JSON)
public class AdminRestController {

    private RuleServiceManager ruleServiceManager;
    private Map<String, Object> uiConfig;

    @Resource
    public void setRuleServiceManager(RuleServiceManager ruleServiceManager) {
        this.ruleServiceManager = ruleServiceManager;
    }

    @Resource
    public void setUiConfig(Map<String, Object> uiConfig) {
        this.uiConfig = uiConfig;
    }

    /**
     * @return a list of descriptions of published OpenL services.
     */
    @GET
    @Path("/services")
    public Response getServiceInfo() {
        return Response.ok(ruleServiceManager.getServicesInfo()).build();
    }

    /**
     * @return a list of descriptions of published OpenL services with serverSettings.
     */
    @GET
    @Path("/ui/info")
    public Response getServiceInfoWithSettings() {
        HashMap<String, Object> info = new HashMap<>(uiConfig);
        info.put("services", ruleServiceManager.getServicesInfo());
        return Response.ok(info).build();
    }

    /**
     * @return a list of method descriptors of the given OpenL service.
     */
    @GET
    @Path("/services/{serviceName}/methods/")
    public Response getServiceMethodNames(@PathParam("serviceName") final String serviceName) {
        return okOrNotFound(ruleServiceManager.getServiceMethods(serviceName));
    }

    private Response okOrNotFound(Object entity) {
        return Response.status(entity == null ? Response.Status.NOT_FOUND : Response.Status.OK).entity(entity).build();
    }
}
