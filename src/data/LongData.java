package data;

import joining.result.ResultTuple;
import org.eclipse.collections.api.list.primitive.IntList;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.BitSet;
import java.util.Collection;

/**
 * Represents content of long column.
 *
 * @author immanueltrummer
 */
public class LongData extends ColumnData implements Serializable {
    /**
     * Holds long data.
     */
    public final long[] data;

    /**
     * Initializes data array for given cardinality.
     *
     * @param cardinality number of rows
     */
    public LongData(int cardinality) {
        super(cardinality);
        this.data = new long[cardinality];
    }

    @Override
    public int compareRows(int row1, int row2) {
        if (isNull.get(row1) && isNull.get(row2)) {
            return 0;
        } else if (isNull.get(row1) || isNull.get(row2)) {
            return 2;
        } else {
            return Long.compare(data[row1], data[row2]);
        }
    }

    @Override
    public int hashForRow(int row) {
        return Long.hashCode(data[row]);
    }

    @Override
    public void swapRows(int row1, int row2) {
        // Swap values
        long tempValue = data[row1];
        data[row1] = data[row2];
        data[row2] = tempValue;
        // Swap NULL values
        super.swapRows(row1, row2);
    }

    @Override
    public void store(String path) throws Exception {
        Files.createDirectories(Paths.get(path).getParent());
        FileOutputStream fileOut = new FileOutputStream(path);
        ObjectOutputStream objOut = new ObjectOutputStream(fileOut);
        objOut.writeObject(this);
        objOut.close();
        fileOut.close();
    }

    @Override
    public ColumnData copyRows(IntList rowsToCopy) {
        LongData copyColumn = new LongData(rowsToCopy.size());
        int copiedRowCtr = 0;
        for (int i = 0; i < rowsToCopy.size(); i++) {
            int row = rowsToCopy.get(i);
            // Treat special case: insertion of null values
            if (row == -1) {
                copyColumn.data[copiedRowCtr] = 0;
                copyColumn.isNull.set(copiedRowCtr);
            } else {
                copyColumn.data[copiedRowCtr] = data[row];
                copyColumn.isNull.set(copiedRowCtr, isNull.get(row));
            }
            ++copiedRowCtr;
        }
        return copyColumn;
    }

    @Override
    public ColumnData copyRows(Collection<ResultTuple> tuples, int tableIdx) {
        LongData copyColumn = new LongData(tuples.size());
        int copiedRowCtr = 0;
        for (ResultTuple compositeTuple : tuples) {
            int baseTuple = compositeTuple.baseIndices[tableIdx];
            copyColumn.data[copiedRowCtr] = data[baseTuple];
            copyColumn.isNull.set(copiedRowCtr, isNull.get(baseTuple));
            ++copiedRowCtr;
        }
        return copyColumn;
    }

    @Override
    public ColumnData copyRows(BitSet rowsToCopy) {
        LongData copyColumn = new LongData(rowsToCopy.cardinality());
        int copiedRowCtr = 0;
        for (int row = rowsToCopy.nextSetBit(0); row != -1;
             row = rowsToCopy.nextSetBit(row + 1)) {
            copyColumn.data[copiedRowCtr] = data[row];
            copyColumn.isNull.set(copiedRowCtr, isNull.get(row));
            ++copiedRowCtr;
        }
        return copyColumn;
    }
}
