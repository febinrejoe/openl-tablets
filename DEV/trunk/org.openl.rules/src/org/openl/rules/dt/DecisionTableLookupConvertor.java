package org.openl.rules.dt;

import java.util.ArrayList;
import java.util.List;

import org.openl.exception.OpenLCompilationException;
import org.openl.rules.table.CoordinatesTransformer;
import org.openl.rules.table.GridRegion;
import org.openl.rules.table.GridTable;
import org.openl.rules.table.IGrid;
import org.openl.rules.table.IGridRegion;
import org.openl.rules.table.IGridTable;
import org.openl.rules.table.IGridTable;
import org.openl.rules.table.OffSetGridTableHelper;
import org.openl.rules.table.TransformedGridTable;

/**
 * Lookup table is a decision table that is created by transforming lookup
 * tables to create a single-column return value.<br><br>
 * 
 * The lookup values could appear either left of the lookup table or on top of
 * it.<br><br>
 * 
 * The values on the left will be called <b>"vertical"</b> and values on top will be
 * called <b>"horizontal"</b>.<br><br> 
 * 
 * The table should have at least one vertical condition column, it can have
 * the Rule column, it (in theory) might have vertical Actions which will be
 * processed the same way as vertical conditions, it must have one or more
 * Horizontal Conditions, and exactly one (optional in the future release) <b>RET</b>
 * column<br><br>. <b>RET</b> section can be placed in any place of lookup headers row, after 
 * vertical conditions (for users convenience).
 * 
 * The Horizontal Conditions will be marked <b>HC1</b>, <b>HC2</b> etc. The first HC column or RET column
 * will mark the starting column of the lookup matrix
 */

public class DecisionTableLookupConvertor {

    public static final int HEADER_ROW = 0;
    public static final int EXPR_ROW = 1;
    public static final int PARAM_ROW = 2;
    public static final int DISPLAY_ROW = 3;
    
    private List<IGridTable> hcHeaders = new ArrayList<IGridTable>();
    private IGridTable retTable;

    public IGridTable convertTable(IGridTable table) throws OpenLCompilationException {
        
        IGridTable originaltable = OffSetGridTableHelper.offSetTable(table);

        IGridTable headerRow = originaltable.getRow(HEADER_ROW);

        int firstLookupColumn = findFirstLookupColumn(headerRow);        
        loadHorizConditionsAndReturnColumns(headerRow, firstLookupColumn);
        validateLookupSection();      

        IGridRegion displayRowRegion = getDisplayRowRegion(originaltable);
        
        int firstLookupGridColumn = headerRow.getColumn(firstLookupColumn).getGridTable().getGridColumn(0, 0);        
        
        IGrid grid = table.getGridTable().getGrid();
        
        processHorizConditionsHeaders(displayRowRegion, firstLookupGridColumn, grid);
        
        IGridTable lookupValuesTable = getLookupValuesTable(originaltable, firstLookupGridColumn, grid);
        
        isMultiplier(lookupValuesTable);
        
        CoordinatesTransformer transformer = getTransformer(headerRow, table, lookupValuesTable);
        
        return new TransformedGridTable(table.getGridTable(), transformer);        
    }    
    
    /**
     * 
     * @param headerRow row with lookup table headers.
     * @return physical index from grid table, indicating first empty cell in the header row 
     */
    private int findFirstEmptyCellInHeader(IGridTable headerRow) {
        int ncol = headerRow.getGridTable().getGridWidth();        
        for (int columnIndex = 0; columnIndex < ncol; columnIndex++) {
            String headerStr = headerRow.getGridTable().getCell(columnIndex, 0).getStringValue();

            if (headerStr == null) {
                return columnIndex;
            }
        }
        return 0;
    }

    private CoordinatesTransformer getTransformer(IGridTable headerRow, IGridTable table, 
            IGridTable lookupValuesTable) throws OpenLCompilationException {
        int retColumnStart = findRetColumnStart(headerRow);
        int firstEmptyCell = findFirstEmptyCellInHeader(headerRow);
        int retTableWidth = retTable.getGridTable().getGridWidth();
        
        if (isRetLastColumn(retColumnStart, retTableWidth, firstEmptyCell)) {
            return new TwoDimensionDecisionTableTranformer(table.getGridTable(), lookupValuesTable, retTableWidth);
        } else {
            return new LookupHeadersTransformer(table.getGridTable(), lookupValuesTable, retTableWidth, retColumnStart, 
                firstEmptyCell);
        }
    }
    
    /**
     * Checks if the RET section is the last one in the header row
     * 
     * @param retColumnStart index, indicating beginning of RET section 
     * @param retTableWidth width of RET section
     * @param firstEmptyCell index, indicating first empty cell in the header
     * @return true if RET section is the last one
     */
    private boolean isRetLastColumn(int retColumnStart, int retTableWidth, int firstEmptyCell) {
        return retColumnStart + retTableWidth == firstEmptyCell;    
    }
    
    /**
     * Finds the physical index from grid table, indicating beginning of RET section.
     * 
     * @param headerRow row with lookup table headers. For example:<br>
     * <table cellspacing="2">
     * <tr>
     * <td align="center" bgcolor="#8FCB52"><b>C1</b></td>
     * <td align="center" bgcolor="#8FCB52"><b>C2</b></td>
     * <td align="center" bgcolor="#8FCB52"><b>C3</b></td>
     * <td align="center" bgcolor="#8FCB52"><b>HC1</b></td>
     * <td align="center" bgcolor="#8FCB52"><b>HC2</b></td>
     * <td align="center" bgcolor="#8FCB52"><b>HC3</b></td>
     * <td align="center" bgcolor="#8FCB52"><b>RET1</b></td>
     * </tr>    
     * </table>
     * 
     * @return the physical index from grid table, indicating beginning of RET section 
     * @throws OpenLCompilationException if there is no RET section in the table.
     */
    private int findRetColumnStart(IGridTable headerRow) throws OpenLCompilationException {
        int ncol = headerRow.getGridTable().getGridWidth();

        for (int columnIndex = 0; columnIndex < ncol; columnIndex ++) {
            String headerStr = headerRow.getGridTable().getCell(columnIndex, 0).getStringValue();

            if (headerStr != null) {
                headerStr = headerStr.toUpperCase();

                if (DecisionTableHelper.isValidRetHeader(headerStr)) { 
                    return columnIndex;
                }               
            }
        }
        throw new OpenLCompilationException("Lookup table must have at least one RET column");
    }

    private void processHorizConditionsHeaders(IGridRegion displayRowRegion, int firstLookupGridColumn, IGrid grid) 
        throws OpenLCompilationException {
        IGridRegion hcHeadersRegion = new GridRegion(displayRowRegion, IGridRegion.LEFT, firstLookupGridColumn);
        IGridTable hcHeaderTable = new GridTable(hcHeadersRegion, grid);

        validateHCHeaders(hcHeaderTable);
    }

    private IGridTable getLookupValuesTable(IGridTable originaltable, int firstLookupGridColumn, IGrid grid) {
        IGridTable valueTable = originaltable.rows(DISPLAY_ROW + 1);

        IGridRegion lookupValuesRegion = new GridRegion(valueTable.getGridTable().getRegion(),
            IGridRegion.LEFT,
            firstLookupGridColumn);
        
        return  new GridTable(lookupValuesRegion, grid);
    }

    private void isMultiplier(IGridTable lookupValuesTable) throws OpenLCompilationException {
        int retTableWidth = retTable.getGridTable().getGridWidth();
        int lookupTableWidth = lookupValuesTable.getGridWidth();
        
        boolean isMultiplier = lookupTableWidth % retTableWidth == 0; 
//            lookupTableWidth/retTableWidth*retTableWidth == lookupTableWidth;
        
        if (!isMultiplier) {
            String message = String.format("The width of the lookup table(%d) is not a multiple of the RET width(%d)", lookupTableWidth, retTableWidth);
            throw new OpenLCompilationException(message);            
        }
    }

    private IGridRegion getDisplayRowRegion(IGridTable originaltable) {        
        IGridTable tableWithDisplay = originaltable.rows(DISPLAY_ROW);
        IGridTable displayRow = tableWithDisplay.getRow(0);
        IGridRegion displayRowRegion = displayRow.getGridTable().getRegion();
        return displayRowRegion;
    }

    private void validateHCHeaders(IGridTable hcHeaderTable) throws OpenLCompilationException {

        String message = String.format("The width of the horizontal keys must be equal to the number of the %s headers", 
            DecisionTableColumnHeaders.HORIZONTAL_CONDITION.getHeaderKey());
        assertEQ(hcHeaders.size(),
            hcHeaderTable.getGridTable().getGridHeight(),
            message);
    }

    private void assertEQ(int v1, int v2, String message) throws OpenLCompilationException {

        if (v1 == v2)
            return;

        throw new OpenLCompilationException(message);
    }
    
    /**
     * 
     * @param headerRow row with lookup table headers. For example:
     * <table cellspacing="2">
     * <tr>
     * <td align="center" bgcolor="#8FCB52"><b>C1</b></td>
     * <td align="center" bgcolor="#8FCB52"><b>C2</b></td>     
     * <td align="center" bgcolor="#8FCB52"><b>HC1</b></td>
     * <td align="center" bgcolor="#8FCB52"><b>HC2</b></td>     
     * <td align="center" bgcolor="#8FCB52"><b>RET1</b></td>
     * </tr>    
     * </table>
     * In this case the return will be <code>2</code>.
     * 
     * @return NOTE!!! it returns an index of logical column!
     * @throws OpenLCompilationException when there is no lookup headers.
     */
    private int findFirstLookupColumn(IGridTable headerRow) throws OpenLCompilationException {        
        int ncol = headerRow.getGridWidth();

        for (int columnIndex = 0; columnIndex < ncol; columnIndex ++) {
            String headerStr = headerRow.getColumn(columnIndex).getGridTable().getCell(0, 0).getStringValue();

            if (headerStr != null) {
                headerStr = headerStr.toUpperCase();

                if (!isValidSimpleDecisionTableHeader(headerStr)) { // if the header in the column is not a valid header 
                    // for common Decision Table, we consider that this column is going to be the beginning for Lookup table section.
                    return columnIndex;
                }               
            }
        }
        throw new OpenLCompilationException("Lookup table must have at least one horizontal condition");
    }
        
    private boolean isValidSimpleDecisionTableHeader(String headerStr) {
        if (DecisionTableHelper.isValidRuleHeader(headerStr) || 
                DecisionTableHelper.isValidConditionHeader(headerStr) || 
                DecisionTableHelper.isValidCommentHeader(headerStr)) {
            return true;
        }
        return false;
    }

    private void loadHorizConditionsAndReturnColumns(IGridTable rowHeader, int firstLookupColumn) throws OpenLCompilationException {

        int ncol = rowHeader.getGridWidth();

        for (; firstLookupColumn < ncol; firstLookupColumn++) {

            IGridTable htable = rowHeader.getColumn(firstLookupColumn);
            String headerStr = htable.getGridTable().getCell(0, 0).getStringValue();

            if (headerStr != null) {
                headerStr = headerStr.toUpperCase();

                if (DecisionTableHelper.isValidHConditionHeader(headerStr)) {
                    loadHorizontalCondition(htable);                    
                } else if (DecisionTableHelper.isValidRetHeader(headerStr)) {
                    loadReturnColumn(htable);                   
                } else {
                    String message = String.format("Lookup Table allow here only %s or %s columns: %s", 
                        DecisionTableColumnHeaders.HORIZONTAL_CONDITION.getHeaderKey(), DecisionTableColumnHeaders.RETURN.getHeaderKey(), headerStr);
                    throw new OpenLCompilationException(message);
                }                
            }           
        }
    }

    private void loadReturnColumn(IGridTable htable) throws OpenLCompilationException {
        if (retTable != null) {
            throw new OpenLCompilationException(String.format("Lookup Table can have only one %s column",
                DecisionTableColumnHeaders.RETURN.getHeaderKey()));
        }

        // assertTableWidth(1, htable,
        // DecisionTableColumnHeaders.RETURN.getHeaderKey());
        retTable = htable;
    }

    private void loadHorizontalCondition(IGridTable htable) throws OpenLCompilationException {
        // if (retTable != null) {
        // throw new
        // OpenLCompilationException(String.format("%s column must be the last one",
        // DecisionTableColumnHeaders.RETURN.getHeaderKey()));
        // }

        hcHeaders.add(htable);
        assertTableWidth(1, htable, DecisionTableColumnHeaders.HORIZONTAL_CONDITION.getHeaderKey());
    }

    private void validateLookupSection() throws OpenLCompilationException {
        if (hcHeaders.size() == 0) {
            String message = String.format("Lookup Table must have at least one Horizontal Condition (%s1)", 
                DecisionTableColumnHeaders.HORIZONTAL_CONDITION.getHeaderKey());
            throw new OpenLCompilationException(message);
        }

        if (retTable == null) {
            String message = String.format("Lookup Table must have %s column", 
                DecisionTableColumnHeaders.RETURN.getHeaderKey());
            throw new OpenLCompilationException(message);
        }
        
    }

    private void assertTableWidth(int w, IGridTable htable, String type) throws OpenLCompilationException {
        if (htable.getGridTable().getGridWidth() == w) {
            return;
        }

        throw new OpenLCompilationException(String.format("Column %s must have width=%s", type, w));
    }

}
