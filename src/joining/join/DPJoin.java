package joining.join;

import config.JoinConfig;
import config.ParallelConfig;
import config.PreConfig;
import expressions.ExpressionInfo;
import expressions.compilation.KnaryBoolEval;
import joining.plan.JoinOrder;
import joining.plan.LeftDeepPlan;
import joining.progress.tree.TreeProgressTracker;
import joining.progress.hash.State;
import preprocessing.Context;
import query.QueryInfo;

import java.util.Arrays;
import java.util.List;

/**
 * A multi-way join operator that executes joins in small
 * episodes, using for each episode a newly specified join
 * order. Collects result tuples on specific split table
 * and contains finally a complete join result.
 *
 * @author Ziyun Wei
 *
 */

public class DPJoin extends OldJoin {
    /**
     * Number of (complete and partial) tuples considered
     * during the last invocation.
     */
    public int nrTuples;
    /**
     * How often did we exploit fast backtracking
     * in the join algorithm?
     */
    public int nrFastBacktracks;
    /**
     * Avoids redundant evaluation work by tracking evaluation progress.
     */
    public final TreeProgressTracker tracker;
    /**
     * Identification of the thread running this join operator.
     */
    public final int tid;
    /**
     * Split the table into partitions. Each thread will evaluate and
     * join in specific partition.
     */
    public int splitTable;
    /**
     * The counter the represent the order of join sample.
     */
    public int roundCtr;
    /**
     * The number of down operations for each table.
     * Down operation means one proposeNext function call.
     */
    public final int[] downOps;
    /**
     * The number of down operations for each table.
     * Up operation means one backTrack function call.
     */
    public final int[] upOps;
    /**
     * After one proposeNext call, record how many tuples matched
     * with the prior table.
     */
    public final int[] nrMatchedTuples;
    /**
     * Cached the index access information for more efficient joining.
     */
    public final IndexAccessInfo accessInfo;
    /**
     * Last state after a episode.
     */
    public State lastEndState;
    /**
     * Initializes join algorithm for given input query.
     *
     * @param query			query to process
     * @param preSummary	summary of pre-processing
     * @param budget		budget per episode
     */
    public DPJoin(QueryInfo query, Context preSummary,
                   int budget, int tid) throws Exception {
        super(query, preSummary, budget);
        int nrSplits = query.equiJoinPreds.size();
        this.tracker = new TreeProgressTracker(nrJoined, cardinalities, nrSplits);
        for (ExpressionInfo unaryExpr : query.wherePredicates) {
            // Is it a unary predicate?
            if (unaryExpr.aliasIdxMentioned.size()==1) {
                // (Exactly one table mentioned for unary predicates)
                int aliasIdx = unaryExpr.aliasIdxMentioned.iterator().next();
                KnaryBoolEval eval = predToEval.get(unaryExpr.finalExpression);
                unaryPreds[aliasIdx] = eval;
            }
        }
        this.tid = tid;
        this.downOps = new int[nrJoined];
        this.upOps = new int[nrJoined];
        this.nrMatchedTuples = new int[nrJoined];
        this.accessInfo = new IndexAccessInfo();
        log("preSummary before join: " + preSummary.toString());
    }
    /**
     * Executes a given join order for a given budget of steps
     * (i.e., predicate evaluations). Result tuples are added
     * to result set. Budget and result set are created during
     * the class initialization.
     *
     * @param order         table join order
     */
    @Override
    public double execute(int[] order) throws Exception {
        log("Context:\t" + preSummary.toString());
        log("Join order:\t" + Arrays.toString(order));
        log("Aliases:\t" + Arrays.toString(query.aliases));
        log("Cardinalities:\t" + Arrays.toString(cardinalities));
        // Treat special case: at least one input relation is empty
        for (int tableCtr = 0; tableCtr < nrJoined; ++tableCtr) {
            if (cardinalities[tableCtr] == 0) {
                tracker.isFinished = true;
                return 1;
            }
        }
        // Set default split table
        if (splitTable < 0) {
            splitTable = getSplitTableByCard(order);
        }
        // Lookup or generate left-deep query plan
        JoinOrder joinOrder = new JoinOrder(order);
        LeftDeepPlan plan = planCache.get(joinOrder);
        if (plan == null) {
            plan = new LeftDeepPlan(query, preSummary, predToEval, order, this);
            planCache.put(joinOrder, plan);
        }
        log(plan.toString());
        int splitTableId = plan.predForTables[splitTable];
        // Execute from starting state, save progress, return progress
        State state = tracker.continueFrom(joinOrder, splitTableId);

//        // TODO: table offset over all threads
//        int[] offsets = tracker.tableOffset;

        int[] offsets = new int[nrJoined];
        // Initialize join statistics
        Arrays.fill(downOps, 0);
        Arrays.fill(upOps, 0);
        Arrays.fill(nrMatchedTuples, 0);

        accessInfo.reset();

        executeWithBudget(plan, state, offsets);

        double reward = reward(joinOrder.order,
                tupleIndexDelta, offsets, nrResultTuples);
        tracker.updateProgress(joinOrder, state, splitTableId, roundCtr);
        lastEndState = state;
        return reward;
    }
    /**
     * Evaluates list of given predicates on current tuple
     * indices and returns true iff all predicates evaluate
     * to true.
     *
     * @param preds				predicates to evaluate
     * @param tupleIndices		(partial) tuples
     * @param indexWrappers	    list of join index wrappers
     * @param nextTable	        the current joining table
     *
     * @return					true iff all predicates evaluate to true
     */
    boolean evaluateAll(List<KnaryBoolEval> preds,
                        List<JoinIndexWrapper> indexWrappers,
                        int[] tupleIndices, int nextTable) {
        if (splitTable == nextTable) {
            for (JoinIndexWrapper pred : indexWrappers) {
                if (!pred.inScope(tupleIndices)) {
                    return false;
                }
            }
        }
        for (KnaryBoolEval pred : preds) {
            if (pred.evaluate(tupleIndices) <= 0) {
                return false;
            }
        }
        return true;
    }
    /**
     * Executes a given join order for a given budget of steps
     * (i.e., predicate evaluations). Result tuples are added
     * to result set. Budget and result set are created during
     * the class initialization.
     *
     * @param plan    left-deep query plan fixing join order
     * @param offsets last fully treated index for each table
     * @param state   last tuple visited in each base table before start
     */
    protected void executeWithBudget(LeftDeepPlan plan, State state, int[] offsets) {
        // Extract variables for convenient access
        int nrTables = query.nrJoined;
        int[] tupleIndices = new int[nrTables];
        System.arraycopy(state.tupleIndices, 0, tupleIndices, 0, nrTables);
        // Initialize state and flags to prepare budgeted execution
        int joinIndex = state.lastIndex;
        // Initialize remaining budget
        int remainingBudget = budget;
        // Whether join index was increased in last iteration
        boolean joinIndexInc = false;
        List<List<KnaryBoolEval>> applicablePreds = plan.applicablePreds;
        List<List<JoinIndexWrapper>> joinIndices = plan.joinIndices;
        // initialize join fields
        initializeJoinFields(plan);
        // Execute join order until budget depleted or all input finished -
        // at each iteration start, tuple indices contain next tuple
        // combination to look at.
        while (remainingBudget > 0 && joinIndex >= 0) {
            // Update maximal join index
            maxJoinIndex = Math.max(maxJoinIndex, joinIndex);
            // Get next table in join order
            int nextTable = plan.joinOrder.order[joinIndex];
            int nextCardinality = cardinalities[nextTable];
            // Integrate table offset
            tupleIndices[nextTable] = Math.max(
                    offsets[nextTable], tupleIndices[nextTable]);
            // Evaluate all applicable predicates on joined tuples
            KnaryBoolEval unaryPred = unaryPreds[nextTable];
            if ((PreConfig.PRE_FILTER || unaryPred == null ||
                    unaryPred.evaluate(tupleIndices)>0) &&
                    evaluateAll(applicablePreds.get(joinIndex),
                            joinIndices.get(joinIndex),
                            tupleIndices, nextTable)) {
                nrTuples++;
                // Does current table represent sub-query in
                // not exists clause?
                int newJoinIndex = joinIndex;
                if (query.existsFlags[nextTable] < 0) {
                    tupleIndices[nextTable] = nextCardinality;
                    newJoinIndex = backtrack(plan, cardinalities,
                            tupleIndices, joinIndex, false);
                } else {
                    newJoinIndex = increaseDepth(plan, cardinalities,
                            tupleIndices, joinIndex, true);
                }
                joinIndexInc = newJoinIndex > joinIndex;
                joinIndex = newJoinIndex;
            } else {
                // At least one of applicable predicates evaluates
                // to false - try next tuple in same table.
                tupleIndices[nextTable] = proposeNext(
                        joinIndices.get(joinIndex),
                        nextTable, tupleIndices);
                downOps[nextTable]++;
                // If activated: fully resolve anti-joins
                // by examining whether no tuple matches.
                if (JoinConfig.SIMPLE_ANTI_JOIN &&
                        plan.existsFlags[nextTable] < 0) {
                    while (tupleIndices[nextTable] < nextCardinality &&
                            !evaluateAll(applicablePreds.get(joinIndex),
                                    tupleIndices)) {
                        tupleIndices[nextTable] = proposeNext(
                                joinIndices.get(joinIndex),
                                nextTable, tupleIndices);
                        downOps[nextTable]++;
                        nrTuples++;
                    }
                }
                indexedTuple[nextTable] = true;
                boolean curNoMatch =
                        tupleIndices[nextTable] >= nextCardinality &&
                                joinIndexInc;
                // Cannot find matching tuple in current table?
                if (curNoMatch && (PreConfig.PRE_FILTER ||
                        unaryPred == null)) {
                    joinIndex = backtrackForNoMatch(plan, cardinalities, tupleIndices, joinIndex);
                    upOps[nextTable]++;
                }
                // Special treatment for tables representing
                // Sub-queries within not exists expressions.
                int newJoinIndex = joinIndex;
                if (query.existsFlags[nextTable] < 0) {
                    // No matching tuples in NOT EXISTS sub-query?
                    if (tupleIndices[nextTable] >= nextCardinality) {
                        tupleIndices[nextTable] = nextCardinality - 1;
                        newJoinIndex = increaseDepth(plan, cardinalities,
                                tupleIndices, joinIndex, true);
                    }
                } else {
                    newJoinIndex = backtrack(plan, cardinalities,
                            tupleIndices, joinIndex, false);
                }
                joinIndexInc = joinIndex < newJoinIndex;
                joinIndex = newJoinIndex;
            }
            --remainingBudget;

        }
        // Store tuple index deltas used to calculate reward
        for (int tableCtr = 0; tableCtr < nrTables; ++tableCtr) {
            int start = Math.max(offsets[tableCtr], state.tupleIndices[tableCtr]);
            int end = Math.max(offsets[tableCtr], tupleIndices[tableCtr]);
            tupleIndexDelta[tableCtr] = end - start;
            if (joinIndex == -1 && tableCtr == plan.joinOrder.order[0] &&
                    tupleIndexDelta[tableCtr] <= 0) {
                tupleIndexDelta[tableCtr] = cardinalities[tableCtr] - start;
            }
        }
        // Save final state
        state.lastIndex = joinIndex;
        System.arraycopy(tupleIndices, 0, state.tupleIndices, 0, nrTables);
    }
    /**
     * Optimize the split table choices
     * based on the progress in the indexes
     *
     * @param joinOrder         table join order
     * @param splitTable        table to split
     * @return                  utility reward for different split table choices
     */
    public double splitTableReward(int[] joinOrder, int splitTable) {
        double progress = 0;
        double weight = 1;
        int nrThreads = ParallelConfig.JOIN_THREADS;
        for (int joinIndex = 0; joinIndex < nrJoined; joinIndex++) {
            int table = joinOrder[joinIndex];
            int indexSize = nrMatchedTuples[table];
            if (indexSize > 0) {
                int downOperations = downOps[table];
                int upOperations = upOps[table];
                weight *= indexSize;
                progress += weight * (table == splitTable ?
                        (nrThreads * downOperations - indexSize * upOperations) :
                        (downOperations - indexSize * upOperations));
            }
        }
        return progress;
    }

    @Override
    public boolean isFinished() {
        return tracker.isFinished || lastEndState.lastIndex < 0;
    }

    /**
     * Get the split table candidate
     * based on cardinalities of tables.
     *
     * @param joinOrder     join order
     *
     * @return              the default split table
     */
    public int getSplitTableByCard(int[] joinOrder) {
        if (ParallelConfig.JOIN_THREADS == 1) {
            return 0;
        }
        int splitLen = 5;
        int splitSize = ParallelConfig.PRE_BATCH_SIZE;
        int splitTable = joinOrder[0];
        int end = Math.min(splitLen, nrJoined);
        int start = nrJoined < splitLen ? 0 : 1;
        for (int i = start; i < end; i++) {
            int table = joinOrder[i];
            int cardinality = cardinalities[table];
            if (cardinality >= splitSize && query.existsFlags[table] == 0) {
                splitTable = table;
                break;
            }
        }
        return splitTable;
    }
}
