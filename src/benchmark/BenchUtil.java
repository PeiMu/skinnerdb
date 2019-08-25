package benchmark;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import buffer.BufferManager;
import catalog.CatalogManager;
import config.NamingConfig;
import joining.JoinProcessor;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import postprocessing.PostProcessor;
import preprocessing.Context;
import preprocessing.Preprocessor;
import query.QueryInfo;
import statistics.JoinStats;

/**
 * Several auxiliary methods for benchmarking SkinnerDB.
 * 
 * @author immanueltrummer
 *
 */
public class BenchUtil {
	/**
	 * Parses queries in all '.sql' files that are found
	 * in given directory and returns mapping from file
	 * names to queries.
	 * 
	 * @param dirPath	path to directory to read queries from
	 * @return			ordered mapping from file names to queries
	 * @throws Exception
	 */
	public static Map<String, PlainSelect> readAllQueries(
			String dirPath) throws Exception {
		Map<String, PlainSelect> nameToQuery = 
				new TreeMap<String, PlainSelect>(
						Collections.reverseOrder());
		File dir = new File(dirPath);
		for (File file : dir.listFiles()) {
			if (file.getName().endsWith(".sql")) {
				String sql = new String(Files.readAllBytes(file.toPath()));
				System.out.println(sql);
				Statement sqlStatement = CCJSqlParserUtil.parse(sql);
				Select select = (Select)sqlStatement;
				PlainSelect plainSelect = (PlainSelect)select.getSelectBody();
				nameToQuery.put(file.getName(), plainSelect);				
			}
		}
		return nameToQuery;
	}
	/**
	 * Writes header row of benchmark result file.
	 * 
	 * @param benchOut	channel to benchmark file
	 */
	public static void writeBenchHeader(PrintWriter benchOut) {
		benchOut.println("Query\tMillis\tPreMillis\tPostMillis\tTuples\t"
				+ "Iterations\tLookups\tNrIndexEntries\tnrUniqueLookups\t" 
				+ "NrUctNodes\tNrPlans\tJoinCard\tNrSamples\tAvgReward\t"
				+ "MaxReward\tTotalWork");
	}
	/**
	 * Executes given query, measures various metrics and writes
	 * into new row via given writer.
	 * 
	 * @param queryName		name of query to process
	 * @param sql			the query SQL
	 * @param benchOut		channel for benchmark results
	 * @throws Exception
	 */
	public static void benchQuery(String queryName, PlainSelect sql, 
			PrintWriter benchOut) throws Exception {
		System.out.println(queryName);
		System.out.println(sql.toString());
		long startMillis = System.currentTimeMillis();
		QueryInfo query = new QueryInfo(sql, false, -1, -1, null);
		Context preSummary = Preprocessor.process(query);
		long preMillis = System.currentTimeMillis() - startMillis;
		JoinProcessor.process(query, preSummary);
		long postStartMillis = System.currentTimeMillis();
		PostProcessor.process(query, preSummary, 
				NamingConfig.FINAL_RESULT_NAME, true);
		long postMillis = System.currentTimeMillis() - postStartMillis;
		long totalMillis = System.currentTimeMillis() - startMillis;
		// Get cardinality of Skinner join result
		int skinnerJoinCard = CatalogManager.getCardinality(
				NamingConfig.JOINED_NAME);
		// Generate output
		benchOut.print(queryName + "\t");
		benchOut.print(totalMillis + "\t");
		benchOut.print(preMillis + "\t");
		benchOut.print(postMillis + "\t");
		benchOut.print(JoinStats.nrTuples + "\t");
		benchOut.print(JoinStats.nrIterations + "\t");
		benchOut.print(JoinStats.nrIndexLookups + "\t");
		benchOut.print(JoinStats.nrIndexEntries + "\t");
		benchOut.print(JoinStats.nrUniqueIndexLookups + "\t");
		benchOut.print(JoinStats.nrUctNodes + "\t");
		benchOut.print(JoinStats.nrPlansTried + "\t");
		benchOut.print(skinnerJoinCard + "\t");
		benchOut.print(JoinStats.nrSamples + "\t");
		benchOut.print(JoinStats.avgReward + "\t");
		benchOut.print(JoinStats.maxReward + "\t");
		benchOut.println(JoinStats.totalWork);
		benchOut.flush();
		// Clean up
		BufferManager.unloadTempData();
		CatalogManager.removeTempTables();
	}
}
