/**
 * Created Dec 3, 2006
 */
package org.openl.binding;

import java.net.URISyntaxException;
import java.net.URL;

import junit.framework.TestCase;

import org.openl.base.INamedThing;
import org.openl.main.SourceCodeURLConstants;
import org.openl.rules.dtx.IDecisionTable;
import org.openl.rules.lang.xls.binding.XlsModuleOpenClass;
import org.openl.rules.test.Tools;
import org.openl.types.IOpenMethod;
import org.openl.types.impl.CompositeMethod;
import org.openl.vm.trace.ITracerObject;
import org.openl.vm.trace.Tracer;

/**
 * @author snshor
 */
public class TestDependencies extends TestCase {

    private static final String FILE_NAME = "org/openl/binding/TestBinding.xls";

    public void testDependencies() throws URISyntaxException {

        XlsModuleOpenClass xmo = _createModule();

        for (IOpenMethod m : xmo.getMethods()) {
            BindingDependencies bd = new BindingDependencies();

            if (m instanceof IDecisionTable) {
                IDecisionTable dt = (IDecisionTable) m;
                dt.updateDependency(bd);

            } else if (m instanceof CompositeMethod) {
                CompositeMethod cm = (CompositeMethod) m;
                cm.updateDependency(bd);

            } else {
                System.out.println("Method " + m.getName() + " has type " + m.getClass());
                continue;
            }

            System.out.println();
            System.out.println(m.getName());
            System.out.println(bd);
        }
    }

    public void testTracer() throws Exception {

        Tracer.initialize();

        URL url = this.getClass().getClassLoader().getResource(FILE_NAME);

        Object res = Tools.run(url.toURI().getPath(), "hello1", new Object[]{new Integer(23)});
        System.out.println(res);

        Iterable<ITracerObject> children = Tracer.getRoot().getChildren();
        for (ITracerObject child : children) {
            printTO(child, 0);
        }
        Tracer.destroy();
    }

    void printTO(ITracerObject to, int level) {

        for (int i = 0; i < level * 2; i++) {
            System.out.print(' ');
        }

        System.out.println("TRACE: " + to.getDisplayName(INamedThing.REGULAR));
        System.out.println(SourceCodeURLConstants.AT_PREFIX + to.getUri());

        Iterable<ITracerObject> children = to.getChildren();
        for (ITracerObject child : children) {
            printTO(child, level + 1);
        }
    }

    private XlsModuleOpenClass _createModule() throws URISyntaxException {

        URL url = this.getClass().getClassLoader().getResource(FILE_NAME);
        return Tools.createModule(url.toURI().getPath());
    }
}