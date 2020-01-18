package indexing;

import java.util.Arrays;

import com.koloboke.collect.map.IntIntCursor;
import com.koloboke.collect.map.IntIntMap;
import com.koloboke.collect.map.hash.HashIntIntMaps;

import config.LoggingConfig;
import data.IntData;
import statistics.JoinStats;

/**
 * Indexes integer values (not necessarily unique).
 * 
 * @author immanueltrummer
 *
 */
public class IntIndex extends Index {
	/**
	 * Integer data that the index refers to.
	 */
	public final IntData intData;
	/**
	 * After indexing: maps search key to index
	 * of first position at which associated
	 * information is stored.
	 */
	public IntIntMap keyToPositions;
	/**
	 * Cache value of last index access.
	 */
	int lastValue = -1;
	/**
	 * Cache position returned during last
	 * index access.
	 */
	int lastPos = -1;
	/**
	 * Cache tuple returned during 
	 * last index access.
	 */
	int lastTuple = -1;
	/**
	 * Counts number of occurrences for each key within
	 * a given data batch, returns a map from keys to
	 * counts.
	 * 
	 * @param batchID	count key occurrences in this data batch
	 * @return			map from keys to counts
	 */
	/*
	IntIntMap countKeysInBatch(int batchID) {
		int[] data = intData.data;
		IntIntMap keyToNr = HashIntIntMaps.newMutableMap();
		int batchSize = cardinality / 500;
		int startRow = batchID * batchSize;
		int endRow = Math.min(cardinality-1, startRow + batchSize-1);
		for (int i=startRow; i<=endRow; ++i) {
			// Don't index null values
			if (!intData.isNull.get(i)) {
				int value = data[i];
				int nr = keyToNr.getOrDefault(value, 0);
				keyToNr.put(value, nr+1);
			}
		}
		return keyToNr;
	}
	*/
	/**
	 * Combines key counts from two batches.
	 * 
	 * @param keyCount1	key occurrence frequencies in first batch
	 * @param keyCount2	key occurrence frequencies in second batch	
	 * @return			combined key count
	 */
	/*
	IntIntMap combineKeyCounts(IntIntMap keyCount1, IntIntMap keyCount2) {
		IntIntMap newKeyCount = HashIntIntMaps.newMutableMap();
		newKeyCount.putAll(keyCount1);
		IntIntCursor keyToNrCursor = keyCount2.cursor();
		while (keyToNrCursor.moveNext()) {
			int key = keyToNrCursor.key();
			int curCount2 = keyToNrCursor.value();
			int curCount1 = keyCount1.getOrDefault(key, 0);
			newKeyCount.put(key, curCount1 + curCount2);
		}
		return newKeyCount;
	}
	*/
	/**
	 * Create index on the given integer column.
	 * 
	 * @param intData	integer data to index
	 */
	public IntIndex(IntData intData) {
		super(intData.cardinality);
		long startMillis = System.currentTimeMillis();
		// Extract info
		this.intData = intData;
		int[] data = intData.data;
		// Count for each key the number of occurrences
		int[] sortedData = Arrays.copyOf(data, cardinality);
		Arrays.parallelSort(sortedData);
		IntIntMap keyToNr = HashIntIntMaps.newMutableMap();
		int nrOccurrences = 0;
		int curKey = sortedData[0];
		for (int i=0; i<cardinality; ++i) {
			nrOccurrences++;
			// About to start new value?
			if (i==cardinality-1 || curKey != sortedData[i+1]) {
				keyToNr.put(curKey, nrOccurrences);
				nrOccurrences = 0;
				curKey = i<cardinality-1?sortedData[i+1]:0;
			}
		}
		/*
		if (cardinality > 10000 && ParallelConfig.PARALLEL) {
			int batchSize = cardinality/500;
			int nrBatches = (int)Math.ceil((double)cardinality/batchSize);
			keyToNr = IntStream.range(0, nrBatches).parallel().
					mapToObj(b -> countKeysInBatch(b)).
					reduce(HashIntIntMaps.newMutableMap(), 
						(m1, m2) -> combineKeyCounts(m1,m2));
		} else {
		*/
		/*
		IntIntMap keyToNr = HashIntIntMaps.newMutableMap();
		for (int i=0; i<cardinality; ++i) {
			// Don't index null values
			if (!intData.isNull.get(i)) {
				int value = data[i];
				//keyToNr.merge(value, 1, Integer::sum);
				int nr = keyToNr.getOrDefault(value, 0);
				keyToNr.put(value, nr+1);
			}
		}
		*/
		// Assign each key to the appropriate position offset
		int nrKeys = keyToNr.size();
		log("Number of keys:\t" + nrKeys);
		keyToPositions = HashIntIntMaps.newMutableMap(nrKeys);
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
		log("Prefix sum:\t" + prefixSum);
		// Generate position information
		positions = new int[prefixSum];
		for (int i=0; i<cardinality; ++i) {
			if (!intData.isNull.get(i)) {
				int key = data[i];
				int startPos = keyToPositions.get(key);
				positions[startPos] += 1;
				int offset = positions[startPos];
				int pos = startPos + offset;
				positions[pos] = i;				
			}
		}
		// Output statistics for performance tuning
		if (LoggingConfig.INDEXING_VERBOSE) {
			long totalMillis = System.currentTimeMillis() - startMillis;
			log("Created index for integer column with cardinality " + 
					cardinality + " in " + totalMillis + " ms.");
		}
		// Check index if enabled
		IndexChecker.checkIndex(intData, this);
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
	public int nextTuple(int value, int prevTuple) {
		// Get start position for indexed values
		int firstPos = keyToPositions.getOrDefault(value, -1);
		// No indexed values?
		if (firstPos < 0) {
			JoinStats.nrUniqueIndexLookups += 1;
			return cardinality;
		}
		// Can we return first indexed value?
		int firstTuple = positions[firstPos+1];
		if (firstTuple>prevTuple) {
			return firstTuple;
		}
		// Get number of indexed values
		int nrVals = positions[firstPos];
		// Update index-related statistics
		JoinStats.nrIndexEntries += nrVals;
		if (nrVals==1) {
			JoinStats.nrUniqueIndexLookups += 1;
		}
		// Restrict search range via binary search
		int lowerBound = firstPos + 1;
		// Exploit lookup cache if possible
		if (lastPos != -1 && lastValue == value && 
				lastTuple <= prevTuple) {
			lowerBound = lastPos + 1;
		}
		int upperBound = firstPos + nrVals;
		while (upperBound-lowerBound>1) {
			int middle = lowerBound + (upperBound-lowerBound)/2;
			if (positions[middle] > prevTuple) {
				upperBound = middle;
			} else {
				lowerBound = middle;
			}
		}
		// Get next tuple
		for (int pos=lowerBound; pos<=upperBound; ++pos) {
			if (positions[pos] > prevTuple) {
				// Cache details about lookup
				lastValue = value;
				lastPos = pos;
				lastTuple = positions[pos];
				// Return tuple at position
				return lastTuple;
			}
		}
		// No suitable tuple found
		lastPos = -1;
		return cardinality;
	}
	/**
	 * Returns the number of entries indexed
	 * for the given value.
	 * 
	 * @param value	count indexed tuples for this value
	 * @return		number of indexed values
	 */
	public int nrIndexed(int value) {
		int firstPos = keyToPositions.getOrDefault(value, -1);
		if (firstPos<0) {
			return 0;
		} else {
			return positions[firstPos];
		}
	}
}
