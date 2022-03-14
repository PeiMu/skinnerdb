package joining.parallel.indexing;

import com.koloboke.collect.IntCollection;
import com.koloboke.collect.map.IntIntCursor;
import com.koloboke.collect.map.IntIntMap;
import com.koloboke.collect.map.hash.HashIntIntMaps;
import com.koloboke.collect.map.hash.HashLongIntMaps;
import com.koloboke.collect.set.IntSet;
import config.ParallelConfig;
import config.PreConfig;
import data.DoubleData;
import data.IntData;
import joining.parallel.threads.ThreadPool;
import predicate.Operator;
import query.ColumnRef;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.stream.IntStream;

public class IntPartitionIndex extends PartitionIndex {
    /**
     * Integer data that the index refers to.
     */
    public final IntData intData;
    /**
     * After indexing: maps search key to index
     * of first position at which associated
     * information is stored.
     */
    public final IntIntMap keyToPositions;
    /**
     * Number of unique keys.
     */
    public final int nrKeys;
    /**
     * Number of threads.
     */
    public final int nrThreads;
    /**
     * Data is distributed to different scopes of threads.
     */
    public final byte[] scopes;
    /**
     * The associated column reference mentioned in the query.
     */
    public final ColumnRef queryRef;
    /**
     * The associated column reference mentioned in the query.
     */
    public AtomicIntegerArray filteredNumbers = null;
    /**
     * Create index on the given integer column.
     *
     * @param intData       integer data to index.
     * @param nrThreads     the number of threads.
     * @param colRef        column reference.
     * @param queryRef      column reference mentioned in the query.
     * @param origin         original index associated with the column.
     * @param policy        joining.parallel policy.
     */
    public IntPartitionIndex(IntData intData, int nrThreads, ColumnRef colRef, ColumnRef queryRef,
                             IntPartitionIndex origin, IndexPolicy policy) {
        super(intData.cardinality);
        // Extract info
        this.nrThreads = nrThreads;
        this.intData = intData;
        this.scopes = new byte[this.cardinality];
        this.queryRef = queryRef;

        if (policy == IndexPolicy.Key || (origin != null && origin.unique)) {
            this.unique = true;
            keyToPositions = HashIntIntMaps.newMutableMap(this.cardinality);
            positions = null;
            keyColumnIndex(origin);
        }
        else if (policy == IndexPolicy.Sequential) {
            boolean unique = true;
            int[] data = intData.data;
            IntIntMap keyToNr = HashIntIntMaps.newMutableMap(this.cardinality);
            for (int i = 0; i < cardinality; ++i) {
                // Don't index null values
                int value = data[i];
                if (!intData.isNull.get(i) && value != Integer.MIN_VALUE) {
                    int nr = keyToNr.getOrDefault(value, 0);
                    keyToNr.put(value, nr + 1);
                    if (nr > 0 && data[i - 1] != value) {
                        sorted = false;
                    }
                    if (nr > 0) {
                        unique = false;
                    }
                }
            }
            this.unique = unique;
            if (unique) {
                keyToPositions = HashIntIntMaps.newMutableMap(this.cardinality);
            }
            else {
                // Assign each key to the appropriate position offset
                int nrKeys = keyToNr.size();
                keyToPositions = HashIntIntMaps.newMutableMap(nrKeys);
            }
            sequentialIndex(colRef, keyToNr);
            // TODO: detect grouped index

            if (positions != null && PreConfig.GROUP_INDEX) {
                groupIds = keyToPositions.values().toIntArray();
                if (queryRef.columnName.equals("l_orderkey")) {
                    groupPerRow = new int[intData.cardinality];
                    IntStream.range(0, groupIds.length).parallel().forEach(gid -> {
                        int pos = groupIds[gid];
                        int nrValues = positions[pos];
                        for (int posCtr = pos + 1; posCtr <= pos + nrValues; posCtr++) {
                            int row = positions[posCtr];
                            groupPerRow[row] = gid;
                        }
                    });
                }
            }
//            else {
//                groupPerRow = new int[intData.cardinality];
//                Arrays.parallelSetAll(groupPerRow, index -> index);
//            }
            System.out.println(colRef + " " + sorted);
        }
        else if (policy == IndexPolicy.Sparse) {
            keyToPositions = origin.keyToPositions;
            positions = new int[origin.positions.length];
            parallelSparseIndex(origin);
        }
        else {
            int[] data = intData.data;
            // Divide tuples into batches
            List<IntIndexRange> batches = this.split();
            IntIntMap keyToNr = HashIntIntMaps.newMutableMap(this.cardinality);
            batches.parallelStream().forEach(batch -> {
                // Evaluate predicate for each table row
                for (int rowCtr = batch.firstTuple; rowCtr <= batch.lastTuple; ++rowCtr) {
                    if (!intData.isNull.get(rowCtr)) {
                        int value = data[rowCtr];
                        batch.add(value);
                    }
                }
            });
            for (IntIndexRange batch : batches) {
                IntIntCursor keyToNrCursor = batch.valuesMap.cursor();
                while (keyToNrCursor.moveNext()) {
                    int key = keyToNrCursor.key();
                    int value = keyToNrCursor.value();
                    keyToNr.computeIfPresent(key, (k, v) -> v + value);
                    keyToNr.putIfAbsent(key, value);
                }
            }

            int nrKeys = keyToNr.size();
            keyToPositions = HashIntIntMaps.newMutableMap(nrKeys);
            IntIntCursor keyToNrCursor = keyToNr.cursor();
            int prefixSum = 0;
            int len = cardinality + nrKeys;
            positions = new int[len];
            while (keyToNrCursor.moveNext()) {
                int key = keyToNrCursor.key();
                int nr = keyToNrCursor.value();
                keyToPositions.put(key, prefixSum);
                positions[prefixSum] = nr;
                // Advance offset taking into account
                // space for row indices and one field
                // storing the number of following indices.
                int nrFields = nr + 1;
                prefixSum += nrFields;
            }
            int nrBatches = batches.size();
            // calculate prefix sum for each bath in joining.parallel
            IntStream.range(0, nrBatches).parallel().forEach(bid -> {
                IntIndexRange batch = batches.get(bid);
                IntIntCursor batchCursor = batch.valuesMap.cursor();
                batch.prefixMap = HashIntIntMaps.newMutableMap(batch.valuesMap.size());
                while (batchCursor.moveNext()) {
                    int key = batchCursor.key();
                    int prefix = 1;
                    int startPos = keyToPositions.getOrDefault(key, 0);
                    for (int i = 0; i < bid; i++) {
                        prefix += batches.get(i).valuesMap.getOrDefault(key, 0);
                    }
                    batch.prefixMap.put(key, prefix + startPos);
                }
                // Evaluate predicate for each table row
                for (int rowCtr = batch.firstTuple; rowCtr <= batch.lastTuple; ++rowCtr) {
                    if (!intData.isNull.get(rowCtr)) {
                        int value = data[rowCtr];
                        int firstPos = keyToPositions.getOrDefault(value, 0);
                        int pos = batch.prefixMap.computeIfPresent(value, (k, v) -> v + 1) - 1;
                        positions[pos] = rowCtr;
                        int startThread = (pos - firstPos - 1) % nrThreads;
                        scopes[rowCtr] = (byte) startThread;
                    }
                }
            });
        }
        nrKeys = this.keyToPositions.size();
    }

    /**
     * Parallel method optimized for key column.
     * A key column is defined as unique value of each row.
     *
     * @param intIndex     original index associated with the column.
     */
    private void keyColumnIndex(IntPartitionIndex intIndex) {
        int[] data = intData.data;
        for (int row = 0; row < cardinality; ++row) {
            int key = data[row];
            if (!intData.isNull.get(row) && key != Integer.MIN_VALUE) {
                keyToPositions.put(key, row);
            }
        }
    }

    /**
     * Parallel method optimized for sparse column.
     * A sparse column is defined as large cardinality
     * but less number of occurrence for each value.
     *
     * @param index     original index associated with the column.
     */
    private void parallelSparseIndex(IntPartitionIndex index) {
        int[] data = intData.data;
        boolean sorted = index.sorted;
        // Count number of occurrences for each batch.
//        long t1 = System.currentTimeMillis();
        if (sorted) {
            System.out.println("sorted");
            List<IntIndexRange> batches = this.split(true);
            int nrBatches = batches.size();
            IntStream.range(0, nrBatches).parallel().forEach(bid -> {
                IntIndexRange batch = batches.get(bid);
                int first = batch.firstTuple;
                int last = batch.lastTuple;
                // Evaluate predicate for each table row
                for (int rowCtr = first; rowCtr <= last; ++rowCtr) {
                    int value = data[rowCtr];
                    if (!intData.isNull.get(rowCtr) && value != Integer.MIN_VALUE) {
                        int firstPos = keyToPositions.getOrDefault(value, -1);
                        int nr = positions[firstPos];
                        int pos = firstPos + 1 + nr;
                        positions[pos] = rowCtr;
                        int startThread = nr % nrThreads;
                        scopes[rowCtr] = (byte) startThread;
                        positions[firstPos]++;
                    }
                }
            });
        }
        else {
            System.out.println("unsorted: " + intData.data.length);
            List<IntIndexRange> batches = this.split(false);
            int nrBatches = batches.size();
            // Atomic Implementation
            AtomicIntegerArray integerArray = new AtomicIntegerArray(index.positions.length);
            if (index.filteredNumbers == null) {
                IntStream.range(0, nrBatches).parallel().forEach(bid -> {
                    IntIndexRange batch = batches.get(bid);
                    int first = batch.firstTuple;
                    int last = batch.lastTuple;
                    for (int rowCtr = first; rowCtr <= last; ++rowCtr) {
                        int value = data[rowCtr];
                        if (!intData.isNull.get(rowCtr) && value != Integer.MIN_VALUE) {
                            int firstPos = keyToPositions.getOrDefault(value, -1);
                            int nr = integerArray.incrementAndGet(firstPos);
                            int pos = firstPos + nr;
                            positions[pos] = rowCtr;
                        }
                    }
                });
                keyToPositions.values().parallelStream().forEach(pos -> {
                    int nr = integerArray.get(pos);
                    if (nr > 0) {
                        positions[pos] = nr;
                        Arrays.sort(positions, pos + 1, pos + nr + 1);
                        IntStream.range(pos+1, pos+1+nr).forEach(ctr ->
                                scopes[positions[ctr]] = (byte) ((ctr - pos - 1) % nrThreads));
                    }
                });
            }
            else {
                ExecutorService executorService = ThreadPool.executorService;
                List<Future<Integer>> futures = new ArrayList<>();
                for (int tid = 0; tid < ParallelConfig.EXE_THREADS; tid++) {
                    int finalTid = tid;
                    futures.add(executorService.submit(() -> {
                        int base = data.length / ParallelConfig.EXE_THREADS;
                        int remaining = data.length - base * ParallelConfig.EXE_THREADS;
                        int first = finalTid * base + Math.min(remaining, finalTid);
                        int last = first + base + (finalTid < remaining ? 1 : 0);
                        for (int rowCtr = first; rowCtr < last; ++rowCtr) {
                            int value = data[rowCtr];
                            if (!intData.isNull.get(rowCtr) && value != Integer.MIN_VALUE) {
                                int firstPos = keyToPositions.getOrDefault(value, -1);
                                int nr = integerArray.incrementAndGet(firstPos);
                                int pos = firstPos + nr;
                                positions[pos] = rowCtr;
                                if (nr == index.filteredNumbers.get(firstPos)) {
                                    positions[firstPos] = nr;
                                    Arrays.sort(positions, firstPos + 1, firstPos + nr + 1);
                                    IntStream.range(firstPos+1, firstPos+1+nr).forEach(ctr ->
                                            scopes[positions[ctr]] = (byte) ((ctr - firstPos - 1) % nrThreads));
                                }
                            }
                        }
                        return 1;
                    }));
                }
                int count = 0;
                for (Future<Integer> batchCount : futures) {
                    try {
                        count += batchCount.get();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * Sequentially generate index for each column.
     * This function is called when the data is loading and the size is small.
     *
     * @param colRef
     */
    private void sequentialIndex(ColumnRef colRef, IntIntMap keyToNr) {
        int[] data = intData.data;
        if (unique) {
            for (int row = 0; row < cardinality; ++row) {
                int key = data[row];
                if (!intData.isNull.get(row) && key != Integer.MIN_VALUE) {
                    keyToPositions.put(key, row);
                }
            }
        }
        else {
            int prefixSum = 0;
            IntIntCursor keyToNrCursor = keyToNr.cursor();
            while (keyToNrCursor.moveNext()) {
                int key = keyToNrCursor.key();
                keyToPositions.put(key, prefixSum);
                // Advance offset taking into account
                // space for row indices and one field
                // storing the number of following indices.
                int nrFields = keyToNrCursor.value() + 1;
                prefixSum += nrFields;
            }
            log(colRef + "Prefix sum:\t" + prefixSum);
            // Generate position information
            positions = new int[prefixSum];
            for (int i = 0; i < cardinality; ++i) {
                int key = data[i];
                if (!intData.isNull.get(i) && key != Integer.MIN_VALUE) {
                    int startPos = keyToPositions.get(key);
                    positions[startPos] += 1;
                    int offset = positions[startPos];
                    int pos = startPos + offset;
                    positions[pos] = i;
                    scopes[i] = (byte) ((offset - 1) % nrThreads);
                }
            }
        }
    }

    /**
     * Splits table with given cardinality into tuple batches
     * according to the configuration for joining.parallel processing.
     *
     * @return list of row ranges (batches)
     */
    public List<IntIndexRange> split() {
        List<IntIndexRange> batches = new ArrayList<>();
        int batchSize = Math.max(ParallelConfig.PRE_INDEX_SIZE, cardinality / 500);
        for (int batchCtr = 0; batchCtr * batchSize < cardinality;
             ++batchCtr) {
            int startIdx = batchCtr * batchSize;
            int tentativeEndIdx = startIdx + batchSize - 1;
            int endIdx = Math.min(cardinality - 1, tentativeEndIdx);
            IntIndexRange IntIndexRange = new IntIndexRange(startIdx, endIdx, batchCtr);
            batches.add(IntIndexRange);
        }
        return batches;
    }

    /**
     * Splits table with given cardinality into tuple batches
     * according to the configuration for joining.parallel processing.
     *
     * @return list of row ranges (batches)
     */
    public List<IntIndexRange> split(boolean sorted) {
        if (sorted) {
            List<IntIndexRange> batches = new ArrayList<>();
            int batchSize = Math.max(ParallelConfig.PRE_INDEX_SIZE, cardinality / 200);
            int startIdx = 0;
            int tentativeEndIdx = startIdx + batchSize - 1;
            int[] data = intData.data;
            for (int batchCtr = 0; batchCtr * batchSize < cardinality;
                 ++batchCtr) {
                int endIdx = Math.min(cardinality - 1, tentativeEndIdx);
                while (endIdx < cardinality - 1 && data[endIdx + 1] == data[endIdx]) {
                    endIdx = Math.min(cardinality - 1, endIdx + 1);
                }
                IntIndexRange IntIndexRange = new IntIndexRange(startIdx, endIdx, batchCtr);
                batches.add(IntIndexRange);
                startIdx = endIdx + 1;
                tentativeEndIdx = startIdx + batchSize - 1;
                if (startIdx >= cardinality) {
                    break;
                }
            }
            return batches;
        }
        else {
            return split();
        }
    }

    /**
     * Returns index of next tuple with given value
     * or cardinality of indexed table if no such
     * tuple exists.
     *
     * @param value			indexed value
     * @param prevTuple		index of last tuple
     * @return 	index of next tuple or cardinality
     */
    public int nextTuple(int value, int prevTuple, int nextTable, int[] nextSize) {
        if (unique) {
            int onlyRow = keyToPositions.getOrDefault(value, cardinality);
            return onlyRow > prevTuple ? onlyRow : cardinality;
        }
        else {
            // Get start position for indexed values
            int firstPos = keyToPositions.getOrDefault(value, -1);
            // No indexed values?
            if (firstPos < 0) {
                return cardinality;
            }
            // Can we return first indexed value?
            int firstTuple = positions[firstPos + 1];
            if (firstTuple > prevTuple) {
                return firstTuple;
            }
            // Get number of indexed values
            int nrVals = positions[firstPos];
            if (nextSize != null)
                nextSize[nextTable] = nrVals;
            // Restrict search range via binary search
            int lowerBound = firstPos + 1;
            int upperBound = firstPos + nrVals;
            while (upperBound - lowerBound > 1) {
                int middle = lowerBound + (upperBound - lowerBound) / 2;
                if (positions[middle] > prevTuple) {
                    upperBound = middle;
                } else {
                    lowerBound = middle;
                }
            }
            // Get next tuple
            for (int pos = lowerBound; pos <= upperBound; ++pos) {
                if (positions[pos] > prevTuple) {
                    return positions[pos];
                }
            }
            // No suitable tuple found
            return cardinality;
        }
    }

    /**
     * Returns index of next tuple with given value
     * or cardinality of indexed table if no such
     * tuple exists. In this function we only run within partitions.
     *
     * @param value     indexed value
     * @param prevTuple index of last tuple
     * @param tid       thread id
     * @return index of next tuple or cardinality
     */
    public int nextTupleInScope(int value, int priorIndex, int prevTuple, int tid, int nextTable, int[] nextSize) {
        tid = (priorIndex + tid) % nrThreads;
        if (unique) {
            if (tid != 0) {
                return cardinality;
            }
            else {
                int onlyRow = keyToPositions.getOrDefault(value, cardinality);
                return onlyRow > prevTuple ? onlyRow : cardinality;
            }
        }
        else {
            // Get start position for indexed values
            int firstPos = keyToPositions.getOrDefault(value, -1);
            // No indexed values?
            if (firstPos < 0) {
                return cardinality;
            }
            // Can we return first indexed value?
            int nrVals = positions[firstPos];
            if (nextSize != null)
                nextSize[nextTable] = nrVals;
            int firstOffset = tid + 1;
            if (firstOffset > nrVals) {
                return cardinality;
            }
            int firstTuple = positions[firstPos + firstOffset];
            if (firstTuple > prevTuple) {
                return firstTuple;
            }
            // Get number of indexed values
            int lastOffset = (nrVals - 1) / nrThreads * nrThreads + tid + 1;
            // if the offset is beyond the array?
            if (lastOffset > nrVals) {
                lastOffset -= nrThreads;
            }
            int threadVals = (lastOffset - firstOffset) / nrThreads + 1;
            // Update index-related statistics
            // Restrict search range via binary search
            int lowerBound = 0;
            int upperBound = threadVals - 1;
            while (upperBound - lowerBound > 1) {
                int middle = lowerBound + (upperBound - lowerBound) / 2;
                int middleOffset = firstPos + middle * nrThreads + tid + 1;
                if (positions[middleOffset] > prevTuple) {
                    upperBound = middle;
                } else {
                    lowerBound = middle;
                }
            }
            // Get next tuple
            for (int pos = lowerBound; pos <= upperBound; ++pos) {
                int offset = firstPos + pos * nrThreads + tid + 1;
                int nextTuple = positions[offset];
                if (nextTuple > prevTuple) {
                    return nextTuple;
                }
            }
            // No suitable tuple found
            return cardinality;
        }
    }

    public int nextTupleInScope(int value, int priorIndex, int prevTuple, int tid, int nextTable,
                                int[] nextSize, IntSet finishedThreads) {
        int extra = priorIndex % nrThreads;
        if (unique) {
            if (finishedThreads.contains(nrThreads - priorIndex % nrThreads)) {
                return cardinality;
            }
            else {
                int onlyRow = keyToPositions.getOrDefault(value, cardinality);
                return onlyRow > prevTuple ? onlyRow : cardinality;
            }
        }
        else {
            // Get start position for indexed values
            int firstPos = keyToPositions.getOrDefault(value, -1);
            // No indexed values?
            if (firstPos < 0) {
                return cardinality;
            }
            // Can we return first indexed value?
            int firstTuple = positions[firstPos + 1];
            if (firstTuple > prevTuple) {
                return firstTuple;
            }
            // Get number of indexed values
            int nrVals = positions[firstPos];
            if (nextSize != null)
                nextSize[nextTable] = nrVals;
            // Restrict search range via binary search
            int lowerBound = firstPos + 1;
            int upperBound = firstPos + nrVals;
            while (upperBound - lowerBound > 1) {
                int middle = lowerBound + (upperBound - lowerBound) / 2;
                if (positions[middle] > prevTuple) {
                    upperBound = middle;
                } else {
                    lowerBound = middle;
                }
            }

            int nextBound = lowerBound;
            int nextTuple = positions[nextBound];
            int nextID = (scopes[nextTuple] + nrThreads - extra) % nrThreads;
            while (finishedThreads.contains(nextID) || nextTuple <= prevTuple) {
                nextBound++;
                if (nextBound - firstPos > nrVals) {
                    return cardinality;
                }
                nextTuple = positions[nextBound];
                nextID = (nextID + 1) % nrThreads;
            }
            return nextTuple;
        }
    }

    /**
     * Evaluate the given predicate on current tuple
     * indices and returns true iff all predicates evaluate
     * to true.
     *
     * @param priorVal      value in the prior table.
     * @param curIndex      index of joining table.
     * @return              binary result of evaluation.
     */
    public boolean evaluate(int priorVal, int curIndex) {
        return priorVal == intData.data[curIndex];
    }

    /**
     * Evaluate the given predicate on current tuple
     * indices and returns true iff all predicates evaluate
     * to true and indices are in partitions for given thread.
     *
     * @param priorVal      value in the prior table.
     * @param priorIndex    index in the prior table.
     * @param curIndex      index of joining table.
     * @param tid           thread id
     * @return              binary result of evaluation.
     */
    public boolean evaluateInScope(int priorVal, int priorIndex, int curIndex, int tid) {
        tid = (priorIndex + tid) % nrThreads;
        return priorVal == intData.data[curIndex] && scopes[curIndex] == tid;
    }

    public boolean evaluateInScope(int priorVal, int priorIndex, int curIndex, int tid, IntSet finishedThreads) {
        int fid = (scopes[curIndex] + nrThreads - priorIndex % nrThreads) % nrThreads;
        return priorVal == intData.data[curIndex] && !finishedThreads.contains(fid);
    }

    /**
     * Returns the number of entries indexed
     * for the given value.
     *
     * @param value	count indexed tuples for this value
     * @return		number of indexed values
     */
    public int nrIndexed(int value) {
        if (unique) {
            return keyToPositions.containsKey(value) ? 1 : 0;
        }
        else {
            int firstPos = keyToPositions.getOrDefault(value, -1);
            if (firstPos < 0) {
                return 0;
            } else {
                return positions[firstPos];
            }
        }
    }
    @Override
    public boolean evaluate(int curTuple, Number constant, Operator operator) {
        int target = constant.intValue();
        int value = intData.data[curTuple];
        if (operator == Operator.EqualsTo) {
            return value == target;
        }
        else if (operator == Operator.GreaterThan) {
            return value > target;
        }
        else if (operator == Operator.GreaterThanEquals) {
            return value >= target;
        }
        else if (operator == Operator.MinorThan) {
            return value < target;
        }
        else if (operator == Operator.MinorThanEquals) {
            return value <= target;
        }
        else if (operator == Operator.NotEqualsTo) {
            return value != target;
        }
        else if (operator == Operator.NotEqualsAll) {
            return !keyToPositions.containsKey(target);
        }
        return false;
    }

    @Override
    public boolean exist(Number constant, Operator operator) {
        int target = constant.intValue();
        int startPos = keyToPositions.getOrDefault(target, -1);
        if (operator == Operator.EqualsTo) {
            if (unique) {
                return startPos >= 0;
            }
            else {
                return startPos >= 0 && positions[startPos] >= 0;
            }
        }
        else if (operator == Operator.NotEqualsTo) {
            return keyToPositions.size() > 1 || !keyToPositions.containsKey(target);
        }
        return false;
    }

    @Override
    public Number getNumber(int curTuple) {
        return intData.data[curTuple];
    }

    @Override
    public void sortRows() {
        sortedRow = IntStream.range(0, cardinality)
                .boxed().parallel().sorted((o1, o2) -> {
                    int d1 = intData.data[o1];
                    int d2 = intData.data[o2];
                    int diff = d1 - d2;
                    if (diff == 0) {
                        return o1 - o2;
                    }
                    return diff;
                })
                .mapToInt(ele -> ele).toArray();
    }

    @Override
    public IntCollection posSet() {
        return keyToPositions.values();
    }
}