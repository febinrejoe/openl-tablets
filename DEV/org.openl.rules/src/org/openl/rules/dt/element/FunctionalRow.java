package org.openl.rules.dt.element;

import java.util.HashSet;
import java.util.Set;

import org.openl.OpenL;
import org.openl.binding.IBindingContext;
import org.openl.engine.OpenLCellExpressionsCompiler;
import org.openl.exception.OpenLCompilationException;
import org.openl.rules.OpenlToolAdaptor;
import org.openl.rules.binding.RuleRowHelper;
import org.openl.rules.dt.DTScale;
import org.openl.rules.dt.IDecisionTableConstants;
import org.openl.rules.dt.storage.IStorage;
import org.openl.rules.dt.storage.IStorageBuilder;
import org.openl.rules.dt.storage.StorageFactory;
import org.openl.rules.lang.xls.syntax.TableSyntaxNode;
import org.openl.rules.table.IGridTable;
import org.openl.rules.table.ILogicalTable;
import org.openl.rules.table.LogicalTableHelper;
import org.openl.rules.table.SimpleLogicalTable;
import org.openl.rules.table.openl.GridCellSourceCodeModule;
import org.openl.source.IOpenSourceCodeModule;
import org.openl.syntax.exception.SyntaxNodeException;
import org.openl.syntax.exception.SyntaxNodeExceptionCollector;
import org.openl.syntax.exception.SyntaxNodeExceptionUtils;
import org.openl.syntax.impl.IdentifierNode;
import org.openl.syntax.impl.Tokenizer;
import org.openl.types.*;
import org.openl.types.impl.CompositeMethod;
import org.openl.types.impl.MethodSignature;
import org.openl.types.impl.OpenMethodHeader;
import org.openl.types.impl.ParameterDeclaration;
import org.openl.vm.IRuntimeEnv;

/**
 * @author snshor
 *
 */
public abstract class FunctionalRow implements IDecisionRow {

    private static final String NO_PARAM = "P";

    private String name;
    private int row;

    protected CompositeMethod method;

    protected IOpenClass ruleExecutionType;

    private IParameterDeclaration[] params;
    protected IStorage<?>[] storage;

    private ILogicalTable decisionTable;
    private ILogicalTable paramsTable;
    private ILogicalTable codeTable;
    private ILogicalTable presentationTable;

    private DTScale.RowScale scale;

    private int noParamsIndex = 0;

    FunctionalRow(String name, int row, ILogicalTable decisionTable, DTScale.RowScale scale) {

        this.name = name;
        this.row = row;
        this.decisionTable = decisionTable;

        this.paramsTable = decisionTable.getSubtable(IDecisionTableConstants.PARAM_COLUMN_INDEX, row, 1, 1);
        this.codeTable = decisionTable.getSubtable(IDecisionTableConstants.CODE_COLUMN_INDEX, row, 1, 1);
        this.presentationTable = decisionTable
            .getSubtable(IDecisionTableConstants.PRESENTATION_COLUMN_INDEX, row, 1, 1);
        this.scale = scale;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CompositeMethod getMethod() {
        return method;
    }

    @Override
    public IParameterDeclaration[] getParams() {
        return params;
    }

    protected void setParams(IParameterDeclaration[] params) {
        this.params = params;
    }

    @Override
    public void clearParamValues() {
        storage = null;
    }

    @Override
    public IOpenSourceCodeModule getSourceCodeModule() {

        if (method != null) {
            return method.getMethodBodyBoundNode().getSyntaxNode().getModule();
        }

        return null;
    }

    @Override
    public int getNumberOfParams() {
        return params.length;
    }

    /**
     * Whole representation of decision table. Horizontal representation of the table where conditions are listed from
     * top to bottom. And must be readed from left to right</br>
     * Example:
     *
     * <table cellspacing="2">
     * <tr>
     * <td align="center" bgcolor="#ccffff"><b>Rule</b></td>
     * <td align="center" bgcolor="#ccffff"></td>
     * <td align="center" bgcolor="#ccffff"></td>
     * <td align="center" bgcolor="#8FCB52">Rule</td>
     * <td align="center" bgcolor="#8FCB52">Rule1</td>
     * <td align="center" bgcolor="#8FCB52">Rule2</td>
     * <td align="center" bgcolor="#8FCB52">Rule3</td>
     *
     * </tr>
     * <tr>
     * <td align="center" bgcolor="#ccffff"><b>C1</b></td>
     * <td align="center" bgcolor="#ccffff">paramLocal1==paramInc</td>
     * <td align="center" bgcolor="#ccffff">String paramLocal1</td>
     * <td align="center" bgcolor="#ffff99">Local Param 1</td>
     * <td align="center" bgcolor="#ffff99">value11</td>
     * <td align="center" bgcolor="#ffff99">value12</td>
     * <td align="center" bgcolor="#ffff99">value13</td>
     * </tr>
     *
     * <tr>
     * <td align="center" bgcolor="#ccffff"><b>C2</b></td>
     * <td align="center" bgcolor="#ccffff">paramLocal2==paramInc</td>
     * <td align="center" bgcolor="#ccffff">String paramLocal2</td>
     * <td align="center" bgcolor="#ffff99">Local Param 2</td>
     * <td align="center" bgcolor="#ffff99">value21</td>
     * <td align="center" bgcolor="#ffff99">value22</td>
     * <td align="center" bgcolor="#ffff99">value23</td>
     * </tr>
     * </table>
     *
     * @return <code>TRUE</code> if table is horizontal.
     */
    @Override
    public ILogicalTable getDecisionTable() {
        return decisionTable;
    }

    @Override
    public String[] getParamPresentation() {

        int length = paramsTable.getHeight();

        String[] result = new String[length];
        int fromHeight = 0;

        for (int i = 0; i < result.length; i++) {

            int gridHeight = paramsTable.getRow(i).getSource().getHeight();

            IGridTable singleParamGridTable = presentationTable.getSource()
                .getRows(fromHeight, fromHeight + gridHeight - 1);
            result[i] = singleParamGridTable.getCell(0, 0).getStringValue();

            fromHeight += gridHeight;
        }

        return result;
    }

    @Override
    public void prepare(IOpenClass methodType,
            IMethodSignature signature,
            OpenL openl,
            IBindingContext bindingContext,
            RuleRow ruleRow,
            IOpenClass ruleExecutionType,
            TableSyntaxNode tableSyntaxNode) throws Exception {
        this.ruleExecutionType = ruleExecutionType;

        method = generateMethod(signature, openl, bindingContext, methodType);

        OpenlToolAdaptor openlAdaptor = new OpenlToolAdaptor(openl, bindingContext, tableSyntaxNode);

        IOpenMethodHeader header = new OpenMethodHeader(name, null, signature, null);
        openlAdaptor.setHeader(header);

        prepareParamValues(method, openlAdaptor, ruleRow);

        if (bindingContext.isExecutionMode()) {
            decisionTable = null;
            paramsTable = null;
            codeTable = null;
            presentationTable = null;
        }

    }

    protected IParameterDeclaration[] getParams(IOpenSourceCodeModule methodSource,
            IMethodSignature signature,
            IOpenClass declaringClass,
            IOpenClass methodType,
            OpenL openl,
            IBindingContext bindingContext) throws Exception {

        if (params == null) {

            Set<String> paramNames = new HashSet<>();
            int length = paramsTable.getHeight();

            params = new IParameterDeclaration[length];

            for (int i = 0; i < length; i++) {
                ILogicalTable paramTable = paramsTable.getRow(i);
                IOpenSourceCodeModule source = new GridCellSourceCodeModule(paramTable.getSource(), bindingContext);

                IParameterDeclaration parameterDeclaration = getParameterDeclaration(source,
                    methodSource,
                    signature,
                    declaringClass,
                    methodType,
                    length == 1,
                    openl,
                    bindingContext);

                String paramName = parameterDeclaration.getName();

                if (paramNames.contains(paramName)) {
                    throw SyntaxNodeExceptionUtils.createError("Duplicated parameter name: " + paramName, source);
                }

                paramNames.add(paramName);

                params[i] = parameterDeclaration;
            }
        }

        return params;
    }

    private void prepareParamValues(CompositeMethod method, OpenlToolAdaptor ota, RuleRow ruleRow) throws Exception {

        int len = nValues();

        IParameterDeclaration[] paramDecl = getParams(method.getMethodBodyBoundNode().getSyntaxNode().getModule(),
            method.getSignature(),
            method.getDeclaringClass(),
            method.getType(),
            ota.getOpenl(),
            ota.getBindingContext());

        boolean[] paramIndexed = getParamIndexed(paramDecl);

        IStorageBuilder<?>[] builders = makeStorageBuilders(len, paramDecl);
        SyntaxNodeExceptionCollector syntaxNodeExceptionCollector = new SyntaxNodeExceptionCollector();

        int actualStorageSize = scale.getActualSize(len);

        for (int i = 0; i < actualStorageSize; i++) {
            int ruleN = scale.getLogicalIndex(i);
            loadParamsFromColumn(ota, ruleRow, paramDecl, paramIndexed, syntaxNodeExceptionCollector, ruleN, builders);
        }

        syntaxNodeExceptionCollector.throwIfAny("Error:");

        storage = new IStorage<?>[builders.length];
        for (int i = 0; i < builders.length; i++) {
            storage[i] = builders[i].optimizeAndBuild();
        }

    }

    private IStorageBuilder<?>[] makeStorageBuilders(int len, IParameterDeclaration[] paramDecl) {

        int nparams = paramDecl.length;
        IStorageBuilder<?>[] builders = new IStorageBuilder[nparams];
        for (int i = 0; i < builders.length; i++) {
            builders[i] = StorageFactory.makeStorageBuilder(len, scale);
        }

        return builders;
    }

    private void loadParamsFromColumn(OpenlToolAdaptor ota,
            RuleRow ruleRow,
            IParameterDeclaration[] paramDecl,
            boolean[] paramIndexed,
            SyntaxNodeExceptionCollector syntaxNodeExceptionCollector,
            int ruleN,
            IStorageBuilder<?>[] builders) {
        IGridTable paramGridColumn = getValueCell(ruleN).getSource();

        int fromHeight = 0;

        boolean executionMode = ota.getBindingContext().isExecutionMode();

        String ruleName = null;
        if (!executionMode) {
            ruleName = ruleRow == null ? ("R" + (ruleN + 1)) : ruleRow.getRuleName(ruleN);
        }

        for (int j = 0; j < paramDecl.length; j++) {
            if (paramDecl[j] == null) {
                continue;
            }

            int gridHeight = paramsTable.getRow(j).getSource().getHeight();
            IGridTable singleParamGridTable = paramGridColumn.getRows(fromHeight, fromHeight + gridHeight - 1);

            Object loadedValue = null;
            try {
                IOpenClass paramType = paramDecl[j].getType();
                loadedValue = RuleRowHelper.loadParam(LogicalTableHelper.logicalTable(
                    singleParamGridTable), paramType, paramDecl[j].getName(), ruleName, ota, paramIndexed[j]);
            } catch (SyntaxNodeException e) {
                // Avoid repeating error messages for same cell in Lookup
                // tables.
                syntaxNodeExceptionCollector.addSyntaxNodeException(e);
            }
            builders[j].writeObject(loadedValue, ruleN);

            fromHeight += gridHeight;
        }
    }

    private boolean[] getParamIndexed(IParameterDeclaration[] paramDecl) {
        boolean[] paramIndexed = new boolean[paramDecl.length];
        for (int i = 0; i < paramIndexed.length; i++) {
            paramIndexed[i] = paramDecl[i].getType().getAggregateInfo().isAggregate(paramDecl[i].getType());
        }
        return paramIndexed;
    }

    Object[] mergeParams(Object target, Object[] dtParams, IRuntimeEnv env, int ruleN) {

        if (dtParams == null) {
            dtParams = new Object[0];
        }

        Object[] newParams = new Object[dtParams.length + getNumberOfParams()];

        System.arraycopy(dtParams, 0, newParams, 0, dtParams.length);
        loadValues(newParams, dtParams.length, ruleN, target, dtParams, env);

        return newParams;
    }

    @Override
    public ILogicalTable getValueCell(int column) {
        return decisionTable.getSubtable(column + IDecisionTableConstants.SERVICE_COLUMNS_NUMBER, row, 1, 1);
    }

    public ILogicalTable getCodeTable() {
        return codeTable;
    }

    public ILogicalTable getParamsTable() {
        return paramsTable;
    }

    public int nValues() {
        return decisionTable.getWidth() - IDecisionTableConstants.SERVICE_COLUMNS_NUMBER;
    }

    private String makeParamName() {
        noParamsIndex += 1;

        return (NO_PARAM + noParamsIndex).intern();
    }

    private CompositeMethod generateMethod(IMethodSignature signature,
            OpenL openl,
            IBindingContext bindingContext,
            IOpenClass methodType) throws Exception {

        IOpenSourceCodeModule source = getExpressionSource(bindingContext, openl, null, signature, methodType);

        IParameterDeclaration[] methodParams = getParams(source, signature, null, methodType, openl, bindingContext);
        IMethodSignature newSignature = ((MethodSignature) signature).merge(methodParams);
        OpenMethodHeader methodHeader = new OpenMethodHeader(null, methodType, newSignature, null);

        return OpenLCellExpressionsCompiler.makeMethod(openl, source, methodHeader, bindingContext);
    }

    protected IOpenSourceCodeModule getExpressionSource(IBindingContext bindingContext,
            OpenL openl,
            IOpenClass declaringClass,
            IMethodSignature signature,
            IOpenClass methodType) throws Exception {
        return new GridCellSourceCodeModule(codeTable.getSource(), bindingContext);
    }

    /**
     * Gets local parameter declaration from specified source.
     *
     * OpenL support several types of parameters declarations. In common case user should provide the following
     * information: <br>
     * a) <type of parameter> <br>
     * b) <name of parameter>.<br>
     * But in simple cases of parameter usage the information is redundant.
     *
     * OpenL engine uses the following rules when user omitted parameter declaration or part of it:
     *
     * a) if cell with parameter declaration is empty then engine will use the parameter with name "Pn", where n is the
     * number of parameter (1 based) and type what is equals of expression type <br>
     *
     * b) if user omitted parameter name then engine will use parameter with name "Pn", where n is the number of
     * parameter (1 based) and type what is specified by user <br>
     *
     * User can use parameters with generated name in his expressions but in this case he should provide type of
     * parameter.
     *
     * @param paramSource source of parameter declaration
     * @param methodSource source of method (cell with expression where used local parameter)
     * @param signature method signature
     * @param declaringClass IOpenClass what declare method
     * @param methodType return type of method
     * @param openl openl context
     * @param bindingContext binding context
     * @return parameter declaration
     * @throws OpenLCompilationException if and error has occurred
     */
    private IParameterDeclaration getParameterDeclaration(IOpenSourceCodeModule paramSource,
            IOpenSourceCodeModule methodSource,
            IMethodSignature signature,
            IOpenClass declaringClass,
            IOpenClass methodType,
            boolean allowEmpty,
            OpenL openl,
            IBindingContext bindingContext) throws OpenLCompilationException {

        IdentifierNode[] nodes = Tokenizer.tokenize(paramSource, " \n\r");

        if (nodes.length == 0) {
            if (allowEmpty) {
                try {
                    OpenMethodHeader methodHeader = new OpenMethodHeader(null, methodType, signature, declaringClass);
                    CompositeMethod method = OpenLCellExpressionsCompiler
                        .makeMethod(openl, methodSource, methodHeader, bindingContext);

                    IOpenClass type = method.getBodyType();

                    if (type instanceof NullOpenClass) {
                        String message = "Cannot recognize type of local parameter for expression";
                        throw SyntaxNodeExceptionUtils.createError(message, null, null, methodSource);
                    }

                    String paramName = makeParamName();

                    return new ParameterDeclaration(type, paramName);
                } catch (Exception | LinkageError ex) {
                    throw SyntaxNodeExceptionUtils.createError("Cannot compile expression", ex, null, methodSource);
                }
            } else {
                String errMsg = "Parameter cell format: <type> <name>";
                throw SyntaxNodeExceptionUtils.createError(errMsg, null, null, paramSource);
            }
        }

        if (nodes.length > 2) {
            String errMsg = "Parameter cell format: <type> <name>";
            throw SyntaxNodeExceptionUtils.createError(errMsg, null, null, paramSource);
        }

        String typeCode = nodes[0].getText();
        IOpenClass type = RuleRowHelper.getType(typeCode, nodes[0], bindingContext);

        if (nodes.length == 1) {
            String paramName = makeParamName();
            return new ParameterDeclaration(type, paramName);
        }

        return new ParameterDeclaration(type, nodes[1].getIdentifier());
    }

    @Override
    public Object getParamValue(int paramIndex, int ruleN) {
        return storage[paramIndex].getValue(ruleN);
    }

    @Override
    public boolean isEmpty(int ruleN) {
        for (IStorage<?> aStorage : storage) {
            if (!aStorage.isSpace(ruleN)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean hasFormula(int ruleN) {
        if (storage != null) {
            for (IStorage<?> aStorage : storage) {
                if (aStorage.isFormula(ruleN)) {
                    return true;
                }
            }
        } else {
            IGridTable paramGridColumn = getValueCell(ruleN).getSource();
            return RuleRowHelper.isFormula(new SimpleLogicalTable(paramGridColumn));
        }
        return false;
    }

    @Override
    public int getNumberOfRules() {
        return storage == null || storage.length == 0 ? 0 : storage[0].size();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void loadValues(Object[] dest, int offset, int ruleN, Object target, Object[] tableParams, IRuntimeEnv env) {

        for (int i = 0; i < dest.length - offset; i++) {
            Object value = storage[i].getValue(ruleN);
            if (value instanceof IOpenMethod) {
                value = ((IOpenMethod) value).invoke(target, tableParams, env);
            }
            if (value instanceof ArrayHolder) {
                value = ((ArrayHolder) value).invoke(target, tableParams, env);
            }
            dest[i + offset] = value;
        }

    }

    @Override
    public boolean hasFormulas() {
        if (storage != null) {
            for (IStorage<?> aStorage : storage) {
                if (aStorage.getInfo().getNumberOfFormulas() > 0) {
                    return true;
                }
            }
        } else {
            int len = nValues();
            int actualStorageSize = scale.getActualSize(len);

            for (int i = 0; i < actualStorageSize; i++) {
                int ruleN = scale.getLogicalIndex(i);
                if (hasFormula(ruleN)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Object getStorageValue(int paramNum, int ruleNum) {
        if (storage == null) {
            return null;
        }
        return storage[paramNum].getValue(ruleNum);
    }

    @Override
    public boolean isEqual(int rule1, int rule2) {
        int n = getNumberOfParams();
        for (int i = 0; i < n; i++) {
            Object p1 = getParamValue(i, rule1);
            Object p2 = getParamValue(i, rule2);
            if ((p1 != p2) && (p1 != null && !p1.equals(p2))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return name + " : " + codeTable;
    }

}
