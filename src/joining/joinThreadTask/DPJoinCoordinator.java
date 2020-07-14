package joining.joinThreadTask;

import joining.join.DataParallelJoin;
import joining.progress.hash.State;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The coordinator decides which table to split
 * based on given join order. The coordinator optimizes
 * the choice based on the cost of query estimated by
 * the slowest thread.
 *
 * @author Ziyun Wei
 */
public class DPJoinCoordinator {
    /**
     * Order of tables for the last learning phase.
     */
    private final int[] joinOrder;
    /**
     * Optimal table to split.
     */
    private volatile int splitTable;
    /**
     * Whether threads finish the join phase for
     * each split table.
     */
    private final boolean[][] finishFlags;
    /**
     * The slowest state on the given join order
     */
    public final AtomicReference<State> slowestState;
    /**
     * The flag that represents the first thread to finish.
     */
    public final AtomicBoolean firstFinished;
    /**
     * The threads' last state for each split table.
     */
    public final State[][] threadStates;

    /**
     * Initialization of the split table coordinator.
     *
     * @param nrThreads     number of threads
     * @param nrTables      number of joining tables
     */
    public DPJoinCoordinator(int nrThreads, int nrTables) {
        joinOrder = new int[nrTables];
        finishFlags = new boolean[nrThreads][nrTables];
        splitTable = -1;
        slowestState = new AtomicReference<>(new State(nrTables));
        firstFinished = new AtomicBoolean(false);
        threadStates = new State[nrThreads][nrTables];
    }
    /**
     * Set the join order to another converged join order.
     *
     * @param joinOrder     converged join order
     */
    public void setJoinOrder(int[] joinOrder) {
        System.arraycopy(joinOrder, 0,
                this.joinOrder, 0, joinOrder.length);
    }
    /**
     * Record the split table at tbe end of the episode.
     *
     * @param splitTable     last table to split
     */
    public void setSplitTable(int splitTable) {
        this.splitTable = splitTable;
    }
    /**
     * Optimize the split table for the converged join order.
     * First, re-optimize the split table based on the statistics.
     * Then if the thread calling this function is the slowest thread,
     * update the split table to the current optimal one.
     *
     * @param dpJoin        join operator
     */
    public void optimizeSplitTable(DataParallelJoin dpJoin) {
        State lastState = dpJoin.prevEndState;
        int tid = dpJoin.tid;
        int lastSplitTable = dpJoin.splitTable;
        int optimalTable = -1;
        int nrTables = joinOrder.length;
        double maxTableReward = 0;
        // Optimize the split table for the join order
        for (int table = 0; table < nrTables; table++) {
            if (dpJoin.downOps[table] > 0) {
//                double tableReward = dpJoin.splitTableReward(joinOrder, table);
                double tableReward = dpJoin.splitReward(joinOrder, table);
                if (tableReward > maxTableReward) {
                    maxTableReward = tableReward;
                    optimalTable = table;
                }
            }
        }
        // Find the slowest thread
        boolean isSlowest = true;
        // Check whether the current thread is the slowest one
        for (int i = 0; i < threadStates.length; i++) {
            if (tid != i) {
                State threadState = threadStates[i][lastSplitTable];
                if (threadState == null
                        || (threadState.lastIndex >= 0
                        && threadState.isAhead(joinOrder, lastState, nrTables))) {
                    isSlowest = false;
                    break;
                }
            }
        }
        if (isSlowest) {
//            dpJoin.log("The thread is slow: " + lastState.toString());
            // Update the optimal split table
            final int finalOptimalTable = optimalTable;
            slowestState.updateAndGet(previousState -> {
                if (previousState.isAhead(joinOrder, lastState, nrTables)) {
                    this.splitTable = finalOptimalTable;
                    return lastState;
                }
                else {
                    return previousState;
                }
            });
        }
    }
    /**
     * Set the finished flag to True for
     * given thread id and split table.
     * Then check Whether all threads
     * finish on the split table
     *
     * @param tid           thread id
     * @param splitTable    table to split
     * @return              whether the join phase can be terminated
     */
    public boolean setAndCheckFinished(int tid, int splitTable) {
        finishFlags[tid][splitTable] = true;
        for (boolean[] finishFlag : finishFlags) {
            if (!finishFlag[splitTable]) {
                return false;
            }
        }
        return true;
    }
    /**
     * Get current optimal split table.
     *
     * @param dpJoin        join operator
     * @return              optimal split table
     */
    public int getSplitTable(DataParallelJoin dpJoin) {
        int tid = dpJoin.tid;
        if (splitTable != -1 && finishFlags[tid][splitTable]) {
            return dpJoin.getSplitTableByCard(joinOrder, splitTable, finishFlags[tid]);
        }
        return splitTable;
    }
    /**
     * Get current optimal join order.
     *
     * @return      optimal join order
     */
    public int[] getJoinOrder() {
        return joinOrder;
    }
}
