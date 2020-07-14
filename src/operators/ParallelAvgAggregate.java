package operators;

import buffer.BufferManager;
import catalog.CatalogManager;
import catalog.info.ColumnInfo;
import catalog.info.TableInfo;
import data.ColumnData;
import data.DoubleData;
import data.IntData;
import expressions.ExpressionInfo;
import expressions.compilation.EvaluatorType;
import expressions.compilation.ExpressionCompiler;
import expressions.compilation.UnaryIntEval;
import indexing.Index;
import query.ColumnRef;
import threads.ThreadPool;
import types.SQLtype;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * Calculates the average (total or per group)
 * from a given input column.
 * 
 * @author Ziyun Wei
 *
 */
public class ParallelAvgAggregate {
	/**
	 * Calculates average aggregate from source data for each group
	 * (or total if no groups are specified) and stores
	 * result in given target column.
	 *
	 * @param sourceRef		reference to source column
	 * @param targetRef		store results in this column
	 * @param index			the index corresponding to selected column
	 * @throws Exception
	 */
	public static void executeWithIndex(ColumnRef sourceRef,
										ColumnRef targetRef,
										Index index,
										ExpressionInfo expression) throws Exception {
		// Get information about source column
		SQLtype srcType = CatalogManager.getColumn(sourceRef).type;
		ColumnData srcData = BufferManager.getData(sourceRef);
		// Generate target column
		int targetCard = index.groups.length;
		DoubleData target = new DoubleData(targetCard);
		BufferManager.colToData.put(targetRef, target);
		// Register target column in catalog
		String targetRel = targetRef.aliasName;
		String targetCol = targetRef.columnName;
		TableInfo targetRelInfo = CatalogManager.
				currentDB.nameToTable.get(targetRel);
		ColumnInfo targetColInfo = new ColumnInfo(targetCol,
				srcType, false, false, false, false);
		targetRelInfo.addColumn(targetColInfo);
		// Update catalog statistics on result table
		CatalogManager.updateStats(targetRel);
		int[] positions = index.positions;
		int[] groups = index.groups;
		// Split groups into batches
		List<RowRange> batches = OperatorUtils.split(groups.length, 50, 500);
		ExecutorService executorService = ThreadPool.postExecutorService;
		List<Future<Integer>> dummyFutures = new ArrayList<>();
		// Compile mapping expression
		OperationTest operationTest = new OperationTest();
		expression.finalExpression.accept(operationTest);
		OperationNode operationNode = operationTest.operationNodes.pop();
		// check more mappings
		OperationNode evaluator = operationNode.operator == Operator.Variable ? null : operationNode;

		// Switch according to column type (to avoid casts)
		switch (srcType) {
			case INT: {
				IntData intSrc = (IntData)srcData;
				for (RowRange batch: batches) {
					int startGroup = batch.firstTuple;
					int endGroup = batch.lastTuple;
					dummyFutures.add(executorService.submit(() -> {
						for (int groupID = startGroup; groupID <= endGroup; groupID++) {
							int pos = groups[groupID];
							int groupCard = positions[pos];
							double value = 0;
							for (int i = pos + 1; i <= pos + groupCard; i++) {
								int rid = positions[i];
								value += intSrc.data[rid];
							}
							if (value != 0) {
								value = value / groupCard;
							}
							if (evaluator != null) {
								value = evaluator.evaluate(value);
							}
							target.data[groupID] = value;
						}
						return 0;
					}));
				}
				for (int batchCtr = 0; batchCtr < batches.size(); batchCtr++) {
					Future<Integer> batchFuture = dummyFutures.get(batchCtr);
				}
				break;
			}
			case DOUBLE: {
				DoubleData doubleSrc = (DoubleData)srcData;
				for (RowRange batch: batches) {
					int startGroup = batch.firstTuple;
					int endGroup = batch.lastTuple;
					dummyFutures.add(executorService.submit(() -> {
						for (int groupID = startGroup; groupID <= endGroup; groupID++) {
							int pos = groups[groupID];
							int groupCard = positions[pos];
							double value = 0;
							for (int i = pos + 1; i <= pos + groupCard; i++) {
								int rid = positions[i];
								value += doubleSrc.data[rid];
							}
							if (value != 0) {
								value = value / groupCard;
							}
							if (evaluator != null) {
								value = evaluator.evaluate(value);
							}
							target.data[groupID] = value;
						}
						return 0;
					}));
				}
				for (int batchCtr = 0; batchCtr < batches.size(); batchCtr++) {
					Future<Integer> batchFuture = dummyFutures.get(batchCtr);
				}
				break;
			}
			default:
				throw new Exception("Unsupported type: " + srcType);
		}
	}
}
