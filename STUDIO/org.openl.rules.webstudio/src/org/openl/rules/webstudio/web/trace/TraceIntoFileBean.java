package org.openl.rules.webstudio.web.trace;

import java.io.IOException;
import java.io.Writer;

import javax.activation.MimetypesFileTypeMap;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ManagedProperty;
import javax.faces.bean.SessionScoped;
import javax.servlet.http.HttpServletResponse;

import org.openl.base.INamedThing;
import org.openl.commons.web.jsf.FacesUtils;
import org.openl.commons.web.util.WebTool;
import org.openl.main.SourceCodeURLConstants;
import org.openl.rules.dtx.trace.DTRuleTraceObject;
import org.openl.rules.webstudio.web.test.RunTestHelper;
import org.openl.util.FileUtils;
import org.openl.util.IOUtils;
import org.openl.vm.trace.ITracerObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request scope managed bean for Trace into File functionality.
 * 
 * @author Yury Molchan
 */
@SessionScoped
@ManagedBean
public class TraceIntoFileBean {
    private static final char[] indents = new char[256];

    static {
        for (int i = 0; i < indents.length; i++)
            indents[i] = '\t';
    }

    private final Logger log = LoggerFactory.getLogger(TraceIntoFileBean.class);

    @ManagedProperty("#{runTestHelper}")
    private RunTestHelper runTestHelper;

    public void setRunTestHelper(RunTestHelper runTestHelper) {
        this.runTestHelper = runTestHelper;
    }

    public void traceIntoFile() {
        ITracerObject tracer = runTestHelper.getTraceObject();

        HttpServletResponse response = (HttpServletResponse) FacesUtils.getResponse();

        String outputFileName = "trace.txt";
        WebTool.setContentDisposition(response, outputFileName);

        String contentType = new MimetypesFileTypeMap().getContentType(outputFileName);
        response.setContentType(contentType);

        Writer writer = null;

        try {
            writer = response.getWriter();
            print(tracer, 0, writer);
            writer.close();
        } catch (IOException e) {
            log.error("Error when printing trace", e);
        } finally {
            IOUtils.closeQuietly(writer);
        }

        FacesUtils.getFacesContext().responseComplete();
    }

    private void print(ITracerObject tracer, int level, Writer writer) throws IOException {

        Iterable<ITracerObject> tracerObjects = tracer.getChildren();
        for (ITracerObject aTrace : tracerObjects) {
            if (aTrace instanceof DTRuleTraceObject) {
                // Do not trace index value that is not mapped to any rule.
                // This can be an excluding boundary for example.
                int[] rules = ((DTRuleTraceObject) aTrace).getRules();
                if (rules == null || rules.length == 0) {
                    continue;
                }
            }
            writer.write(indents, 0, level % indents.length);
            writer.write("TRACE: ");
            writer.write(TraceFormatter.getDisplayName(aTrace, INamedThing.REGULAR));
            writer.write('\n');
            writer.write(indents, 0, level % indents.length);
            writer.write(SourceCodeURLConstants.AT_PREFIX);
            writer.write(FileUtils.getBaseName(aTrace.getUri()));
            writer.write('&');
            writer.write(SourceCodeURLConstants.OPENL);
            writer.write('=');
            writer.write('\n');

            print(aTrace, level + 1, writer);
        }
    }
}
