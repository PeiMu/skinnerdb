package joining.parallel.parallelization.search;

import config.JoinConfig;
import config.LoggingConfig;
import config.ParallelConfig;
import joining.parallel.indexing.OffsetIndex;
import joining.parallel.join.OldJoin;
import joining.parallel.join.SPJoin;
import joining.parallel.join.SubJoin;
import joining.parallel.parallelization.Parallelization;
import joining.parallel.progress.ParallelProgressTracker;
import joining.parallel.threads.ThreadPool;
import joining.parallel.uct.NSPNode;
import joining.parallel.uct.SPNode;
import joining.result.ResultTuple;
import logs.LogUtils;
import net.sf.jsqlparser.expression.Expression;
import predicate.NonEquiNode;
import preprocessing.Context;
import query.QueryInfo;
import statistics.JoinStats;
import statistics.QueryStats;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class NewSearchParallelization extends Parallelization {
    /**
     * Multiple join operators for threads
     */
    private List<OldJoin> oldJoins = new ArrayList<>();
    /**
     * initialization of parallelization
     *
     * @param nrThreads the number of threads
     * @param budget    the budget per episode
     * @param query     select query with join predicates
     * @param context   query execution context
     */
    public NewSearchParallelization(int nrThreads, int budget, QueryInfo query, Context context) throws Exception {
        super(nrThreads, budget, query, context);
        // Compile predicates
        Map<Expression, NonEquiNode> predToEval = new HashMap<>();
        int nrJoined = query.nrJoined;
        OffsetIndex[][] threadOffsets = new OffsetIndex[nrThreads][nrJoined];
        for (int i = 0; i < query.nonEquiJoinNodes.size(); i++) {
            // Compile predicate and store in lookup table
            Expression pred = query.nonEquiJoinPreds.get(i).finalExpression;
            NonEquiNode node = query.nonEquiJoinNodes.get(i);
            predToEval.put(pred, node);
        }
        // Initialize multi-way join operator
        for (int threadCtr = 0; threadCtr < nrThreads; threadCtr++) {
            for (int tableCtr = 0; tableCtr < nrJoined; tableCtr++) {
                threadOffsets[threadCtr][tableCtr] = new OffsetIndex();
            }
            OldJoin oldJoin = new OldJoin(query, context, budget,
                    nrThreads, threadCtr, predToEval, threadOffsets);
            oldJoins.add(oldJoin);
        }
    }
    @Override
    public void execute(Set<ResultTuple> resultList) throws Exception {
        List<String>[] logs = new List[nrThreads];
        List<SPTask> tasks = new ArrayList<>();
        // Mutex shared by multiple threads.
        AtomicBoolean isFinished = new AtomicBoolean(false);

        // Initialize UCT join order search tree.
        for (int threadCtr = 0; threadCtr < nrThreads; threadCtr++) {
            logs[threadCtr] = new ArrayList<>();
            SPTask searchTask = new SPTask(
                    query, context, oldJoins.get(threadCtr),
                    threadCtr, nrThreads, isFinished);
            tasks.add(searchTask);
        }
        // Initialize a thread pool.
        ExecutorService executorService = ThreadPool.executorService;
        long executionStart = System.currentTimeMillis();
        List<Future<SearchResult>> futures = executorService.invokeAll(tasks);
        long executionEnd = System.currentTimeMillis();
        JoinStats.exeTime = executionEnd - executionStart;
        futures.forEach(futureResult -> {
            try {
                SearchResult result = futureResult.get();
                resultList.addAll(result.result);
                if (LoggingConfig.PARALLEL_JOIN_VERBOSE) {
                    logs[result.id] = result.logs;
                }

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        });
        long mergeEnd = System.currentTimeMillis();
        JoinStats.mergeTime = mergeEnd - executionEnd;
        long nrSamples = 0;
        JoinStats.nrSamples = nrSamples;
        // Write log to the local file.
        if (LoggingConfig.PARALLEL_JOIN_VERBOSE) {
            if (ParallelConfig.PARALLEL_SPEC == 2) {
                LogUtils.writeLogs(logs, "verbose/search/" + QueryStats.queryName);
            }
            else if (ParallelConfig.PARALLEL_SPEC == 3) {
                LogUtils.writeLogs(logs, "verbose/dynamic_search/" + QueryStats.queryName);
            }
        }
        System.out.println("Result Set: " + resultList.size());
    }
}
