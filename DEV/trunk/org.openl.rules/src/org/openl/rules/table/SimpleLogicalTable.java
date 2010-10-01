package org.openl.rules.table;

/**
 * @author Andrei Astrouski
 */
public class SimpleLogicalTable extends ALogicalTable {

    public SimpleLogicalTable(IGridTable table) {
        super(table);
    }

    public int getWidth() {
        return table.getWidth();
    }

    public int getHeight() {
        return table.getHeight();
    }

    public int findColumnStart(int gridOffset) {
        return gridOffset;
    }

    public int findRowStart(int gridOffset) {
        return gridOffset;
    }

    public int getColumnWidth(int column) {
        return 1;
    }

    public int getRowHeight(int row) {
        return 1;
    }

    public ILogicalTable getSubtable(int column, int row, int width, int height) {
        return LogicalTableHelper.logicalTable(table.getSubtable(column, row, width, height));
    }

}
