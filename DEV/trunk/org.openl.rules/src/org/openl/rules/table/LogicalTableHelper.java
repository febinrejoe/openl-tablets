package org.openl.rules.table;

public class LogicalTableHelper {
    
    private LogicalTableHelper() {}

    public static int calcLogicalColumns(IGridTable table) {
        int columns = 0;
        int cellWidth;
        for (int w = 0; w < table.getWidth(); w += cellWidth, columns++) {
            cellWidth = table.getCell(w, 0).getWidth();
        }
        return columns;
    }

    public static int calcLogicalRows(IGridTable table) {
        int rows = 0;
        int cellHeight;
        for (int h = 0; h < table.getHeight(); h += cellHeight, rows++) {
            cellHeight = table.getCell(0, h).getHeight();
        }
        return rows;
    }

    public static ILogicalTable logicalTable(
            IGridTable table, ILogicalTable columnOffsetsTable, ILogicalTable rowOffsetsTable) {
        int[] columnOffsets = null;
        if (columnOffsetsTable != null && columnOffsetsTable instanceof LogicalTable) {
            columnOffsets = ((LogicalTable)columnOffsetsTable).getColumnOffset();
        }    

        int[] rowOffsets = null;
        if (rowOffsetsTable != null && rowOffsetsTable instanceof LogicalTable) {
            rowOffsets = ((LogicalTable)rowOffsetsTable).getRowOffset();
        }

        if (rowOffsets == null && columnOffsets == null)
            return LogicalTableHelper.logicalTable(table);

        return new LogicalTable(table, columnOffsets, rowOffsets);
    }

    /**
     * @param table Original table.
     * @return Another logical table with correctly calculated height and width.
     */
    public static ILogicalTable logicalTable(IGridTable table) {
        int width = calcLogicalColumns(table);
        int height = calcLogicalRows(table);
        if (width == table.getWidth() && height == table.getHeight()) {
            return new SimpleLogicalTable(table);
        }

        return new LogicalTable(table, width, height);
    }

    /**
     * This method will produce a logical table defined by 2 tables: leftRows
     * and topColumns Both tables are logical tables. Rows in a new table will
     * be defined by rows in leftRows table, and columns by the columns
     * topColumns table. "Left" and "top" points to relative location of
     * defining tables. It should be used only with "normal" orientation
     *
     * @param leftRows
     * @param topColumns
     * @return
     */
    public static ILogicalTable mergeBounds(ILogicalTable leftRows, ILogicalTable topColumns) {
        IGridTable leftRowsGrid = leftRows.getSource();
        if (!leftRowsGrid.isNormalOrientation()) {
            throw new RuntimeException("Left Rows must have Normal Orientation");
        }

        IGridTable topColumnsGrid = topColumns.getSource();
        if (!topColumnsGrid.isNormalOrientation()) {
            throw new RuntimeException("Top Columns must have Normal Orientation");
        }

        IGridRegion leftRowsRegion = leftRowsGrid.getRegion();
        IGridRegion topColumnsRegion = topColumnsGrid.getRegion();

        int rLeft = leftRowsRegion.getRight() + 1;
        int cLeft = topColumnsRegion.getLeft();
        int left = cLeft;
        int startColumn = 0;
        if (cLeft < rLeft) {
            startColumn = topColumns.findColumnStart(rLeft - cLeft);
            left = rLeft;
        }

        int rTop = leftRowsRegion.getTop();
        int cTop = topColumnsRegion.getBottom() + 1;
        int top = rTop;
        int startRow = 0;
        if (rTop < cTop) {
            startRow = leftRows.findRowStart(cTop - rTop);
            top = cTop;
        }

        int right = topColumnsRegion.getRight();
        int bottom = leftRowsRegion.getBottom();

        if (right < left) {
            throw new RuntimeException("Invalid horizontal dimension");
        }

        if (bottom < top) {
            throw new RuntimeException("Invalid vertical dimension");
        }

        IGridTable gt = new GridTable(top, left, bottom, right, leftRowsGrid.getGrid());

        int nRows = leftRows.getHeight() - startRow;
        int nColumns = topColumns.getWidth() - startColumn;

        if (gt.getHeight() == nRows && gt.getWidth() == nColumns) {
            // TODO Light delegator
            return new SimpleLogicalTable(gt);
        }

        int[] rowsOffset = new int[nRows + 1];
        int[] columnsOffset = new int[nColumns + 1];
        int rOffset = 0;
        int i = 0;
        for (; i < nRows; i++) {
            rowsOffset[i] = rOffset;
            rOffset += leftRows.getRowHeight(i + startRow);
        }
        rowsOffset[i] = rOffset;

        int cOffset = 0;
        i = 0;
        for (; i < nColumns; i++) {
            columnsOffset[i] = cOffset;
            cOffset += topColumns.getColumnWidth(i + startColumn);
        }
        columnsOffset[i] = cOffset;

        return new LogicalTable(gt, columnsOffset, rowsOffset);
    }

    /**
     *
     * @return table with 1 column, if necessary transposed, caller is
     *         responsible to check that table is either 1xN or Nx1
     */    
    public static ILogicalTable make1ColumnTable(ILogicalTable t) {
        if (t.getWidth() == 1) {
            return t;
        }
    
        if (t.getHeight() == 1) {
            return t.transpose();
        }
    
        // caller is responsible to check that table is either 1xN or Nx1
        return t;
    
    }

}
