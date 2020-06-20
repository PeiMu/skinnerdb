package joining.join;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import config.LoggingConfig;
import config.PreConfig;
import expressions.ExpressionInfo;
import expressions.compilation.KnaryBoolEval;
import indexing.UniqueIntIndex;
import joining.plan.JoinOrder;
import joining.plan.LeftDeepPlan;
import joining.progress.hash.HashProgressTracker;
import joining.progress.hash.State;
import joining.result.ResultTuple;
import preprocessing.Context;
import query.QueryInfo;
import statistics.JoinStats;

public class OldJoinDepr extends MultiWayJoin {
    /**
     * Re-initialized in each invocation:
     * stores the remaining budget for
     * the current iteration.
     */
    public int remainingBudget;
    /**
     * Maximal join index during 
     * last invocation.
     */
    public int maxJoinIndex;
    /**
     * Number of completed tuples produced
     * during last invocation.
     */
    public int nrResultTuples;
    /**
     * Avoids redundant planning work by storing left deep plans.
     */
    final Map<JoinOrder, LeftDeepPlan> planCache;
    /**
     * Avoids redundant evaluation work by tracking evaluation progress.
     */
    public final HashProgressTracker tracker;
    /**
     * Associates each table index with unary predicates.
     */
    final KnaryBoolEval[] unaryPreds;
    /**
     * Counts number of log entries made.
     */
    int logCtr = 0;
    /**
     * Initializes join algorithm for given input query.
     * 
     * @param query			query to process
     * @param preSummary	summary of pre-processing
     * @param budget		budget per episode
     */
    public OldJoinDepr(QueryInfo query, Context preSummary, 
    		int budget) throws Exception {
        super(query, preSummary, budget);
        this.planCache = new HashMap<>();
        this.tracker = new HashProgressTracker(nrJoined, cardinalities);
        // Collect unary predicates
        this.unaryPreds = new KnaryBoolEval[nrJoined];
        for (ExpressionInfo unaryExpr : query.wherePredicates) {
        	// Is it a unary predicate?
        	if (unaryExpr.aliasIdxMentioned.size()==1) {
            	// (Exactly one table mentioned for unary predicates)
            	int aliasIdx = unaryExpr.aliasIdxMentioned.iterator().next();
            	KnaryBoolEval eval = predToEval.get(unaryExpr.finalExpression);
            	unaryPreds[aliasIdx] = eval;
        	}
        }
        log("preSummary before join: " + preSummary.toString());
    }
    /**
     * Calculates reward for progress during one invocation.
     * 
     * @param joinOrder			join order followed
     * @param tupleIndexDelta	difference in tuple indices
     * @param tableOffsets		table offsets (number of tuples fully processed)
     * @return					reward between 0 and 1, proportional to progress
     */
	double reward(int[] joinOrder, int[] tupleIndexDelta, int[] tableOffsets) {
		double progress = 0;
		double weight = 1;
		for (int pos=0; pos<nrJoined; ++pos) {
			// Scale down weight by cardinality of current table
			int curTable = joinOrder[pos];
			int remainingCard = cardinalities[curTable] - 
					(tableOffsets[curTable]);
			//int remainingCard = cardinalities[curTable];
			weight *= 1.0 / remainingCard;
			// Fully processed tuples from this table
			progress += tupleIndexDelta[curTable] * weight;
		}
		return 0.5*progress + 0.5*nrResultTuples/(double)budget;
	}
    /**
     * Executes a given join order for a given budget of steps
     * (i.e., predicate evaluations). Result tuples are added
     * to result set. Budget and result set are created during
     * the class initialization.
     *
     * @param order   table join order
     */
	@Override
	public double execute(int[] order) throws Exception {
    	log("Context:\t" + preSummary.toString());
    	log("Join order:\t" + Arrays.toString(order));
    	log("Aliases:\t" + Arrays.toString(query.aliases));
    	log("Cardinalities:\t" + Arrays.toString(cardinalities));
    	// Treat special case: at least one input relation is empty
    	for (int tableCtr=0; tableCtr<nrJoined; ++tableCtr) {
    		if (cardinalities[tableCtr]==0) {
    			tracker.isFinished = true;
    			return 1;
    		}
    	}
    	// Lookup or generate left-deep query plan
        JoinOrder joinOrder = new JoinOrder(order);
        LeftDeepPlan plan = planCache.get(joinOrder);
        if (plan == null) {
            plan = new LeftDeepPlan(query, preSummary, predToEval, order);
            planCache.put(joinOrder, plan);
        }
        log(plan.toString());
        // Execute from starting state, save progress, return progress
        State state = tracker.continueFrom(joinOrder);
        //logger.println("Start state " + state);
        int[] offsets = tracker.tableOffset;
        executeWithBudget(plan, state, offsets);
        double reward = reward(joinOrder.order, 
        		tupleIndexDelta, offsets);
        tracker.updateProgress(joinOrder, state);
        return reward;
	}
	/**
	 * Evaluates list of given predicates on current tuple
	 * indices and returns true iff all predicates evaluate
	 * to true.
	 * 
	 * @param preds				predicates to evaluate
	 * @param tupleIndices		(partial) tuples
	 * @return					true iff all predicates evaluate to true
	 */
	boolean evaluateAll(List<KnaryBoolEval> preds, int[] tupleIndices) {
		for (KnaryBoolEval pred : preds) {
			if (pred.evaluate(tupleIndices)<=0) {
				return false;
			}
		}
		return true;
	}
	/**
	 * Propose next tuple index to consider, based on a set of
	 * indices on the join column.
	 * 
	 * @param indexWrappers	list of join index wrappers
	 * @param tupleIndices	current tuple indices
	 * @return				next proposed tuple index
	 */
	int proposeNext(List<JoinIndexWrapper> indexWrappers, 
			int curTable, int[] tupleIndices) {
		if (indexWrappers.isEmpty()) {
			return tupleIndices[curTable]+1;
		}
		if (uniqueIndex[curTable] && indexedTuple[curTable]) {
			return cardinalities[curTable];
		}
		int max = -1;
		for (JoinIndexWrapper wrapper : indexWrappers) {
			int nextRaw = wrapper.nextIndex(tupleIndices);
			int next = nextRaw<0?cardinalities[curTable]:nextRaw;
			max = Math.max(max, next);
		}
		if (max<0) {
			System.out.println(Arrays.toString(tupleIndices));
			System.out.println(indexWrappers.toString());
		}
		return max;
	}
	
	final static int cachedDepth = 3;
	
	ResultTuple exclusionTuple(int[] tupleIndices, 
			int joinIndex, int[] order) {
		ResultTuple exclusion = new ResultTuple(tupleIndices);
		int nrTables = tupleIndices.length;
		for (int i=joinIndex+1; i<nrTables; ++i) {
			int table = order[i];
			exclusion.baseIndices[table] = -1;
		}
		return exclusion;
	}
	
	boolean[] uniqueIndex;
	boolean[] indexedTuple;
	
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
    private void executeWithBudget(LeftDeepPlan plan, State state, int[] offsets) {
        // Extract variables for convenient access
        int nrTables = query.nrJoined;
        int[] tupleIndices = new int[nrTables];
        List<List<KnaryBoolEval>> applicablePreds = plan.applicablePreds;
        List<List<JoinIndexWrapper>> joinIndices = plan.joinIndices;
        // Which tables use indices with unique values?
        uniqueIndex = new boolean[nrTables];
        for (int joinCtr=0; joinCtr<nrTables; ++joinCtr) {
        	int table = plan.joinOrder.order[joinCtr];
        	for (JoinIndexWrapper wrap : joinIndices.get(joinCtr)) {
        		if (wrap.nextIndex instanceof UniqueIntIndex) {
        			uniqueIndex[table] = true;
        		}
        	}
        }
        // Whether tuple at this position comes from index.
        indexedTuple = new boolean[nrTables];
        // Initialize state and flags to prepare budgeted execution
        int joinIndex = state.lastIndex;
        for (int tableCtr = 0; tableCtr < nrTables; ++tableCtr) {
            tupleIndices[tableCtr] = state.tupleIndices[tableCtr];
        }
        int remainingBudget = budget;
        // Maximal join index during current execution
        maxJoinIndex = 0;
        // Number of completed tuples added
        nrResultTuples = 0;
        // Whether at least one matching tuple was found
        // with a specific join index.
        boolean[] matchingTuples = new boolean[nrTables];
        Arrays.fill(matchingTuples, true);
        // Whether join index was increased in last iteration
        boolean joinIndexInc = false;
        // Execute join order until budget depleted or all input finished -
        // at each iteration start, tuple indices contain next tuple
        // combination to look at.
        while (remainingBudget > 0 && joinIndex >= 0) {
        	++JoinStats.nrIterations;
        	// Update maximal join index
        	maxJoinIndex = Math.max(maxJoinIndex, joinIndex);
        	//log("Offsets:\t" + Arrays.toString(offsets));
        	//log("Indices:\t" + Arrays.toString(tupleIndices));
            // Get next table in join order
            int nextTable = plan.joinOrder.order[joinIndex];
            int nextCardinality = cardinalities[nextTable];
            //System.out.println("index:"+joinIndex+", next table:"+nextTable);
            // Integrate table offset
            tupleIndices[nextTable] = Math.max(
                    offsets[nextTable], tupleIndices[nextTable]);
            // Evaluate all applicable predicates on joined tuples
            KnaryBoolEval unaryPred = unaryPreds[nextTable];
            if ((PreConfig.PRE_FILTER || unaryPred == null || 
            		unaryPred.evaluate(tupleIndices)>0) &&
            		evaluateAll(applicablePreds.get(joinIndex), 
            				tupleIndices)
            		/*
            		&&
            		(joinIndex >= cachedDepth || !tracker.excluded.contains(
            				exclusionTuple(tupleIndices, joinIndex, 
            						plan.joinOrder.order)))
            		*/
            		) {
            	++JoinStats.nrTuples;
                // Do we have a complete result row?
                if(joinIndex == plan.joinOrder.order.length - 1) {
                	// Found at least one matching tuple
                	matchingTuples[joinIndex] = true;
                    // Complete result row -> add to result
                	++nrResultTuples;
                    result.add(tupleIndices);
                    tupleIndices[nextTable] = proposeNext(
                    		joinIndices.get(joinIndex), nextTable, tupleIndices);
                    // Have reached end of current table? -> we backtrack.
                    while (tupleIndices[nextTable] >= nextCardinality) {
                        tupleIndices[nextTable] = 0;
                        indexedTuple[nextTable] = false;
                        --joinIndex;
                        if (joinIndex < 0) {
                            break;
                        }
                        
                        
                        
                        nextTable = plan.joinOrder.order[joinIndex];
                        nextCardinality = cardinalities[nextTable];
                        // TODO: check performance impact
                        tupleIndices[nextTable] += 1;
                        /*
                        tupleIndices[nextTable] = proposeNext(
                        		joinIndices.get(joinIndex), 
                        		nextTable, tupleIndices);
                        */
                    }
                    joinIndexInc = false;
                } else {
                    // No complete result row -> complete further
                	matchingTuples[joinIndex] = true;
                    joinIndex++;
                    matchingTuples[joinIndex] = false;
                    joinIndexInc = true;
                    //System.out.println("Current Join Index2:"+ joinIndex);
                }
            } else {
                // At least one of applicable predicates evaluates to false -
                // try next tuple in same table.
                tupleIndices[nextTable] = proposeNext(
                		joinIndices.get(joinIndex), 
                		nextTable, tupleIndices);
                indexedTuple[nextTable] = true;
                int curJoinIdx = joinIndex;
                int curTable = nextTable;
                boolean curNoMatch = 
                		tupleIndices[nextTable] >= nextCardinality && 
                		joinIndexInc;
                // Cannot find matching tuple in current table?
                if (curNoMatch && (PreConfig.PRE_FILTER || 
                		unaryPred == null)) {
                	int maxNextJoinIdx = -1;
            		for (JoinIndexWrapper joinWrap : 
            			joinIndices.get(curJoinIdx)) {
            			if (joinWrap.lastProposed >= nextCardinality) {
                			for (int i=0; i<nrTables; ++i) {
                				if (plan.joinOrder.order[i] == 
                						joinWrap.priorTable) {
                					maxNextJoinIdx = Math.max(
                							i, maxNextJoinIdx);
                				}
                			}
            			}
            		}
            		if (maxNextJoinIdx > -1 && 
            				maxNextJoinIdx < joinIndex-1) {
	                    // Exploit fast back-tracking
	                    for (int i=maxNextJoinIdx+1; i<=joinIndex; ++i) {
	                    	int table = plan.joinOrder.order[i];
	                    	tupleIndices[table] = 0;
	                    	indexedTuple[table] = false;
	                    }
	                    joinIndex = maxNextJoinIdx;
	                    nextTable = plan.joinOrder.order[joinIndex];
	                    nextCardinality = cardinalities[nextTable];
	                    tupleIndices[nextTable] = proposeNext(
	                    		joinIndices.get(joinIndex), 
	                    		nextTable, tupleIndices);
	                    // Update statistics on backtracking
	                    JoinStats.nrFastBacktracks++;
            		}
                }
                // Go back to last table that could make a difference
                // Have reached end of current table? -> we backtrack.
                while (tupleIndices[nextTable] >= nextCardinality) {
                	// TODO: check correctness
                    tupleIndices[nextTable] = 0;
                    indexedTuple[nextTable] = false;
                	//tupleIndices[nextTable] = offsets[nextTable];
                    --joinIndex;
                    if (joinIndex < 0) {
                        break;
                    }
                    // TODO: check performance
                    /*
                    if (joinIndex < cachedDepth) {
                    	tracker.excluded.add(exclusionTuple(
                    			tupleIndices, joinIndex, 
                    			plan.joinOrder.order));
                    }
                    */
                    nextTable = plan.joinOrder.order[joinIndex];
                    nextCardinality = cardinalities[nextTable];
                    // TODO: check performance
                    //tupleIndices[nextTable] += 1;
                    tupleIndices[nextTable] = proposeNext(
                    		joinIndices.get(joinIndex), 
                    		nextTable, tupleIndices);
                }
                joinIndexInc = false;
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
        for (int tableCtr = 0; tableCtr < nrTables; ++tableCtr) {
            state.tupleIndices[tableCtr] = tupleIndices[tableCtr];
        }
    }
    @Override
    public boolean isFinished() {
    	return tracker.isFinished;
    }
    /**
     * Output log text unless the maximal number
     * of log entries has already been reached.
     * 
     * @param logEntry	text to output
     */
    void log(String logEntry) {
    	if (logCtr < LoggingConfig.MAX_JOIN_LOGS) {
    		++logCtr;
    		System.out.println(logCtr + "\t" + logEntry);
    	}
    }
}
