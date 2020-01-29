package operators.parallel;

import buffer.BufferManager;
import catalog.CatalogManager;
import catalog.info.ColumnInfo;
import com.koloboke.collect.map.IntIntCursor;
import com.koloboke.collect.map.hash.HashIntIntMaps;
import config.ParallelConfig;
import data.ColumnData;
import data.IntData;
import indexing.Index;
import joining.parallel.indexing.IntIndexRange;
import operators.Group;
import operators.OperatorUtils;
import operators.RowRange;
import postprocessing.IndexRange;
import query.ColumnRef;
import types.SQLtype;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ParallelGroupBy {
    /**
     * Iterates over rows of input colunms (must have the
     * same cardinality) and calculates consecutive
     * group values that are stored in target column.
     *
     * @param sourceRefs	source column references
     * @param targetRef		store group ID in that column
     * @return				number of groups
     * @throws Exception
     */
    public static Map<Group, GroupIndex> execute(Collection<ColumnRef> sourceRefs,
                              ColumnRef targetRef, int maxSize) throws Exception {
        // Register result column
        String targetTbl = targetRef.aliasName;
        String targetCol = targetRef.columnName;
        ColumnInfo targetInfo = new ColumnInfo(targetCol,
                SQLtype.INT, false, false, false, false);
        CatalogManager.currentDB.nameToTable.get(targetTbl).addColumn(targetInfo);
        // Generate result column and load it into buffer
        String firstSourceTbl = sourceRefs.iterator().next().aliasName;
        int cardinality = CatalogManager.getCardinality(firstSourceTbl);
        IntData groupData = new IntData(cardinality);
        BufferManager.colToData.put(targetRef, groupData);
        // Get data of source columns
        List<ColumnData> sourceCols = new ArrayList<>();
        for (ColumnRef srcRef : sourceRefs) {
            sourceCols.add(BufferManager.getData(srcRef));
        }
        // Fill result column
        Map<Group, GroupIndex> groupIndexListMap = new HashMap<>();


        for (int rowCtr = 0; rowCtr < cardinality; ++rowCtr) {
            Group curGroup = new Group(rowCtr, sourceCols);
            GroupIndex groupIndex = groupIndexListMap.computeIfAbsent(curGroup, group -> {
                int nextGroupID = groupIndexListMap.size();
                return new GroupIndex(nextGroupID, -1);
            });
            groupIndex.addRow(rowCtr);
            groupData.data[rowCtr] = groupIndex.gid;
        }

//        List<GroupIndexRange> batches = split(cardinality);
//        int nrBatches = batches.size();
//        batches.parallelStream().forEach(batch -> {
//            // Evaluate predicate for each table row
//            int first = batch.firstTuple;
//            int last = batch.lastTuple;
//            for (int rowCtr = first; rowCtr <= last; ++rowCtr) {
//                Group curGroup = new Group(rowCtr, sourceCols);
//                batch.add(curGroup, rowCtr - first);
//            }
//        });
//
//        for (GroupIndexRange batch : batches) {
//            batch.prefixMap = HashIntIntMaps.newMutableMap(batch.valuesMap.size());
//            batch.valuesMap.forEach((curGroup, value) -> {
//                int number = value;
//                GroupIndex groupIndex = groupIndexListMap.computeIfAbsent(curGroup, group -> {
//                    int nextGroupID = groupIndexListMap.size();
//                    return new GroupIndex(nextGroupID);
//                });
//                batch.prefixMap.put(groupIndex.gid, groupIndex.number);
//                groupIndex.number += number;
//            });
//        }
//
//        groupIndexListMap.values().parallelStream().forEach(index -> index.rows = new ArrayList<>(index.number));
//
//        IntStream.range(0, nrBatches).parallel().forEach(bid -> {
//            GroupIndexRange batch = batches.get(bid);
//            int first = batch.firstTuple;
//            int last = batch.lastTuple;
//            for (int rowCtr = first; rowCtr <= last; ++rowCtr) {
//                GroupIndex index = groupIndexListMap.get(batch.groups[rowCtr - first]);
//                int gid = index.gid;
//                int offset = batch.prefixMap.getOrDefault(gid, -1);
//                index.rows.set(offset, rowCtr);
//                batch.prefixMap.put(gid, offset + 1);
//                groupData.data[rowCtr] = gid;
//            }
//        });


        // Update catalog statistics
        CatalogManager.updateStats(targetTbl);
        // Retrieve data for
        return groupIndexListMap;
    }

    public static Map<Group, GroupIndex> executeIndex(Collection<ColumnRef> sourceRefs,
                                                 ColumnRef targetRef, Index index) throws Exception {
        // Register result column
        String targetTbl = targetRef.aliasName;
        String targetCol = targetRef.columnName;
        ColumnInfo targetInfo = new ColumnInfo(targetCol,
                SQLtype.INT, false, false, false, false);
        CatalogManager.currentDB.nameToTable.get(targetTbl).addColumn(targetInfo);
        // Generate result column and load it into buffer
        String firstSourceTbl = sourceRefs.iterator().next().aliasName;
        int cardinality = CatalogManager.getCardinality(firstSourceTbl);
        IntData groupData = new IntData(cardinality);
        BufferManager.colToData.put(targetRef, groupData);
        // Get data of source columns
        List<ColumnData> sourceCols = new ArrayList<>();
        for (ColumnRef srcRef : sourceRefs) {
            sourceCols.add(BufferManager.getData(srcRef));
        }
        // Fill result column
        int[] gids = index.groupIds;
        int[] positions = index.positions;
        IntStream.range(0, gids.length).parallel().forEach(gid -> {
            int pos = gids[gid];
            int groupCard = positions[pos];
            for (int i = pos + 1; i <= pos + groupCard; i++) {
                int rowCtr = positions[i];
                groupData.data[rowCtr] = gid;
            }
        });
        // Update catalog statistics
        CatalogManager.updateStats(targetTbl);
        // Retrieve data for
        return null;
    }

    /**
     * Splits table with given cardinality into tuple batches
     * according to the configuration for joining.parallel processing.
     *
     * @return list of row ranges (batches)
     */
    static List<GroupIndexRange> split(int cardinality) {
        List<GroupIndexRange> batches = new ArrayList<>();
        int batchSize = Math.max(ParallelConfig.PRE_INDEX_SIZE, cardinality / 100);
        for (int batchCtr = 0; batchCtr * batchSize < cardinality;
             ++batchCtr) {
            int startIdx = batchCtr * batchSize;
            int tentativeEndIdx = startIdx + batchSize - 1;
            int endIdx = Math.min(cardinality - 1, tentativeEndIdx);
            GroupIndexRange groupIndexRange = new GroupIndexRange(startIdx, endIdx, batchCtr);
            batches.add(groupIndexRange);
        }
        return batches;
    }
}