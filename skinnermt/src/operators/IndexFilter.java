package operators;

import java.util.*;
import java.util.stream.Collectors;

import buffer.BufferManager;
import com.google.common.primitives.Ints;
import expressions.ExpressionInfo;
import expressions.normalization.PlainVisitor;
import indexing.Index;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntListIterator;
import joining.parallel.indexing.IntPartitionIndex;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import query.ColumnRef;
import query.QueryInfo;
import query.SQLexception;
import types.SQLtype;

/**
 * Uses all applicable indices to evaluate a unary
 * predicate and returns set of qualifying row
 * indices. The index filter should only be applied
 * to expressions that pass the IndexTest.
 * 
 * @author Anonymous
 *
 */
public class IndexFilter extends PlainVisitor {
	/**
	 * Query to which index filter is applied.
	 */
	final QueryInfo query;
	/**
	 * Contains indexes of all rows satisfying
	 * the predicate.
	 */
	public final Deque<IntArrayList> qualifyingRows =
			new ArrayDeque<>();
	/**
	 * Contains column names involved for the predicate.
	 */
	public boolean isSameColumn = false;
	public boolean lazyMaterialization = false;
	public int level = 0;
	public final List<Pair<IntPartitionIndex, Integer>> lazyInformation = new ArrayList<>();
	/**
	 * Contains last extracted integer constants.
	 */
	final Deque<Integer> extractedConstants =
			new ArrayDeque<>();
	/**
	 * Contains indexes applicable
	 * for sub-expressions.
	 */
	final Deque<Index> applicableIndices =
			new ArrayDeque<>();
	/**
	 * Contains indexes applicable
	 * for sub-expressions.
	 */
	final Deque<Boolean> fullResults =
			new ArrayDeque<>();
	/**
	 * Contains some properties that decide how to
	 * interpret the results.
	 */
	public boolean isFull = false;
	/**
	 * Whether equalsTo predicate can use
	 * full results.
	 */
	public boolean equalFull = false;
	/**
	 * The type of returned results:
	 * 0: full results.
	 * 1: range.
	 * 2: equal positions.
	 */
	public int resultType = 0;
	/**
	 * Last filtering index.
	 */
	public Index lastIndex;
	/**
	 * Remaining unary predicate information
	 */
	public ExpressionInfo remainingInfo;
	/**
	 * Satisfied rows after applying index
	 */
	public int[] rows;
	/**
	 * Initialize index filter for given query.
	 * 
	 * @param query	meta-data on evaluated query
	 */
	public IndexFilter(QueryInfo query) {
		this.query = query;
	}
	
	@Override
	public void visit(AndExpression and) {
		equalFull = true;
		and.getLeftExpression().accept(this);
		boolean leftFull = fullResults.pop();
		and.getRightExpression().accept(this);
		boolean rightFull = fullResults.pop();
		// Intersect sorted row index lists
		IntArrayList rows1 = qualifyingRows.pop();
		IntArrayList rows2 = qualifyingRows.pop();
		IntArrayList intersectedRows = new IntArrayList(Math.min(rows1.size(), rows2.size()));
		qualifyingRows.push(intersectedRows);
		if (!rows1.isEmpty() && !rows2.isEmpty()) {
			if (!leftFull && !rightFull) {
				resultType = 1;
				int first = Math.max(rows1.getInt(0), rows2.getInt(0));
				int last = Math.min(rows1.getInt(1), rows2.getInt(1));
				intersectedRows.add(first);
				intersectedRows.add(last);
			}
			else {
				resultType = 0;
				IntListIterator row1iter = rows1.iterator();
				IntListIterator row2iter = rows2.iterator();
				int row1 = row1iter.next();
				int row2 = row2iter.next();
				while (row1 !=Integer.MAX_VALUE &&
						row2 != Integer.MAX_VALUE) {
					if (row1 == row2) {
						intersectedRows.add(row1);
						row1 = row1iter.hasNext()?row1iter.next():Integer.MAX_VALUE;
						row2 = row2iter.hasNext()?row2iter.next():Integer.MAX_VALUE;
					} else if (row1 < row2) {
						row1 = row1iter.hasNext()?row1iter.next():Integer.MAX_VALUE;
					} else {
						// row2 < row1
						row2 = row2iter.hasNext()?row2iter.next():Integer.MAX_VALUE;
					}
				}
			}
		}
	}
	
	@Override
	public void visit(OrExpression or) {
		isFull = true;
		equalFull = true;
		lazyMaterialization = isSameColumn;
		int level = this.level;
		this.level++;
		or.getLeftExpression().accept(this);
		or.getRightExpression().accept(this);
		if (lazyMaterialization) {
			if (level == 0) {
				int size = 0;
				for (Pair<IntPartitionIndex, Integer> pair: lazyInformation) {
					IntPartitionIndex index = pair.getKey();
					int pos = pair.getValue();
					if (pos >= 0) {
						if (index.unique) {
							size += 1;
						}
						else {
							int nr = index.positions[pos];
							size += nr;
						}
					}

				}
				int[] allResults = new int[size];
				int start = 0;
				for (Pair<IntPartitionIndex, Integer> pair: lazyInformation) {
					int pos = pair.getValue();
					IntPartitionIndex index = pair.getKey();
					if (pos >= 0) {
						if (index.unique) {
							allResults[start] = pos;
							start += 1;
						}
						else {
							int[] positions = index.positions;
							int nr = positions[pos];
							System.arraycopy(positions, pos + 1, allResults, start, nr);
							start += nr;
						}
					}
				}
				Arrays.parallelSort(allResults, 0, size);
				// Both input lists are non-empty
				IntArrayList mergedRows = new IntArrayList(allResults);
				qualifyingRows.push(mergedRows);
			}
		}
		else {
			// Merge sorted row index lists
			IntArrayList rows1 = qualifyingRows.pop();
			IntArrayList rows2 = qualifyingRows.pop();
			resultType = 0;

			if (rows1.isEmpty()) {
				qualifyingRows.push(rows2);
			} else if (rows2.isEmpty()) {
				qualifyingRows.push(rows1);
			} else {
				Iterator<Integer> row1iter = rows1.iterator();
				Iterator<Integer> row2iter = rows2.iterator();
				int row1 = row1iter.next();
				int row2 = row2iter.next();
				if (row1 == Integer.MAX_VALUE - 1) {
					qualifyingRows.push(rows2);
					return;
				}

				if (row2 == Integer.MAX_VALUE - 1) {
					qualifyingRows.push(rows1);
					return;
				}
				// Both input lists are non-empty
				IntArrayList mergedRows = new IntArrayList(rows1.size() + rows2.size());
				qualifyingRows.push(mergedRows);
				// Assume that tables contain at most Integer.MAX_VALUE - 1 elements
				while (row1 != Integer.MAX_VALUE ||
						row2 != Integer.MAX_VALUE) {
					if (row1 < row2) {
						mergedRows.add(row1);
						row1 = row1iter.hasNext()?row1iter.next():Integer.MAX_VALUE;
					} else if (row2 < row1) {
						mergedRows.add(row2);
						row2 = row2iter.hasNext()?row2iter.next():Integer.MAX_VALUE;
					} else {
						// row1 == row2
						mergedRows.add(row1);
						if (row1iter.hasNext()) {
							row1 = row1iter.next();
						} else {
							row2 = row2iter.next();
						}
					}
				}
			}
		}

	}
	
	@Override
	public void visit(EqualsTo equalsTo) {
		equalsTo.getLeftExpression().accept(this);
		equalsTo.getRightExpression().accept(this);
		// We assume predicate passed the index test so
		// there must be one index and one constant.
		int constant = extractedConstants.pop();
		IntPartitionIndex index = (IntPartitionIndex) applicableIndices.pop();
		int startPos = index.keyToPositions.getOrDefault(constant, -1);
		if (lazyMaterialization) {
			lazyInformation.add(new ImmutablePair<>(index, startPos));
		}
		else {
			if (startPos >= 0) {
				if (index.unique) {
					IntArrayList rows = new IntArrayList();
					rows.add(startPos);
					qualifyingRows.push(rows);
				}
				else {
					int nrEntries = index.positions[startPos];
					// Collect indices of satisfying rows via index
					IntArrayList rows = new IntArrayList(index.positions, startPos + 1, nrEntries);
					qualifyingRows.push(rows);
				}
			}
			else {
				qualifyingRows.push(new IntArrayList());
			}
		}

		isFull = true;
		fullResults.push(true);
		resultType = 0;
	}
	
	@Override
	public void visit(LongValue longValue) {
		extractedConstants.push((int)longValue.getValue());
	}

	@Override
	public void visit(DateTimeLiteralExpression literal) {
		DateValue dateVal = new DateValue(literal.getValue());
		int unixTime = (int)(dateVal.getValue().getTime()/1000);
		extractedConstants.push(unixTime);
	}

	@Override
	public void visit(IntervalExpression interval) {
		// Extract parameter value
		String param = interval.getParameter();
		String strVal = param.substring(1, param.length()-1);
		int intVal = Integer.parseInt(strVal);
		// Treat according to interval type
		String intervalType = interval.getIntervalType().toLowerCase();
		switch (intervalType) {
			case "year":
				extractedConstants.push(intVal * 12);
				break;
			case "month":
				extractedConstants.push(intVal);
				break;
			case "day":
				int daySecs = 24 * 60 * 60 * intVal;
				extractedConstants.push(daySecs);
				break;
			case "hour":
				int hourSecs = 60 * 60 * intVal;
				extractedConstants.push(hourSecs);
				break;
			case "minute":
				int minuteSecs = 60 * intVal;
				extractedConstants.push(minuteSecs);
				break;
			case "second":
				extractedConstants.push(intVal);
				break;
			default:
				System.out.println("Error - unknown interval type");
		}
	}
	
	@Override
	public void visit(StringValue stringValue) {
		// String must be in dictionary due to index test
		String strVal = stringValue.getValue();
		int code = BufferManager.dictionary.getCode(strVal);
		extractedConstants.push(code);
	}
	
	@Override
	public void visit(Column column) {
		// Resolve column reference
		String aliasName = column.getTable().getName();
		String tableName = query.aliasToTable.get(aliasName);
		String columnName = column.getColumnName();
		ColumnRef colRef = new ColumnRef(tableName, columnName);
		// Check for available index
		Index index = BufferManager.colToIndex.get(colRef);
		if (index != null) {
			applicableIndices.push(index);
		}
	}

	@Override
	public void visit(GreaterThan greaterThan) {
		findRange(greaterThan, 0);
		fullResults.push(false);
	}

	@Override
	public void visit(GreaterThanEquals greaterThanEquals) {
		findRange(greaterThanEquals, 1);
		fullResults.push(false);
	}

	@Override
	public void visit(MinorThan minorThan) {
		findRange(minorThan, 2);
		fullResults.push(false);
	}

	@Override
	public void visit(MinorThanEquals minorThanEquals) {
		findRange(minorThanEquals, 3);
		fullResults.push(false);
	}

	public void findRange(ComparisonOperator comparisonOperator, int op) {
		comparisonOperator.getLeftExpression().accept(this);
		comparisonOperator.getRightExpression().accept(this);
		// We assume predicate passed the index test so
		// there must be one index and one constant.
		int constant = extractedConstants.pop();
		Index index = applicableIndices.pop();
		// Collect indices of satisfying rows via index
		IntArrayList rows = new IntArrayList();
		qualifyingRows.push(rows);
		int last = index.cardinality;
		int lowerBound = 0;
		int upperBound = last - 1;
		int first = 0;
		int[] data;
		data = ((IntPartitionIndex)index).intData.data;
		while (upperBound - lowerBound > 1) {
			int middle = lowerBound + (upperBound - lowerBound) / 2;
			int rid = index.sortedRow[middle];
			if (data[rid] > constant) {
				upperBound = middle;
			} else if (data[rid] < constant) {
				lowerBound = middle;
			}
			else if (op == 1 || op == 2){
				upperBound = middle;
			}
			else {
				lowerBound = middle;
			}
		}
		boolean found = false;
		// Get next tuple
		for (int pos = lowerBound; pos <= upperBound; ++pos) {
			int rid = index.sortedRow[pos];
			int value = data[rid];
			if (op == 0) {
				if (value > constant) {
					first = pos;
					found = true;
				}
			}
			else if (op == 1) {
				if (value >= constant) {
					first = pos;
					found = true;
				}
			}
			else if (op == 2) {
				if (value < constant) {
					last = pos + 1;
					found = true;
				}
			}
			else if (op == 3) {
				if (value <= constant) {
					last = pos + 1;
					found = true;
				}
			}
		}
		if (isFull) {
			if (found) {
				for (int i = first; i < last; i++) {
					rows.add(index.sortedRow[i]);
				}
			}
			resultType = 0;
		}
		else {
			if (found) {
				rows.add(first);
				rows.add(last);
				lastIndex = index;
			}
			else {
				rows.add(index.cardinality);
				rows.add(index.cardinality);
				lastIndex = index;
			}
			resultType = 1;
		}
	}

	/**
	 * Returns true iff the left operand is either a date or
	 * a timestamp and the right operand is a year-month
	 * time interval.
	 *
	 * @param leftType		left type of binary expression
	 * @param rightType		right type of binary expression
	 * @return			true iff the operands require special treatment
	 */
	boolean dateYMintervalOp(SQLtype leftType, SQLtype rightType) {
		return (leftType.equals(SQLtype.DATE) ||
				leftType.equals(SQLtype.TIMESTAMP)) &&
				(rightType.equals(SQLtype.YM_INTERVAL));
	}

	SQLtype getDateType(Expression expression) {
		SQLtype sqLtype = SQLtype.ANY_TYPE;
		if (expression instanceof DateTimeLiteralExpression) {
			DateTimeLiteralExpression arg0 = (DateTimeLiteralExpression) expression;
			switch (arg0.getType()) {
				case DATE:
					sqLtype = SQLtype.DATE;
					break;
				case TIME:
					sqLtype = SQLtype.TIME;
					break;
				case TIMESTAMP:
					sqLtype = SQLtype.TIMESTAMP;
					break;
			}
		}
		else if (expression instanceof IntervalExpression) {
			IntervalExpression arg0 = (IntervalExpression) expression;
			String timeUnit = arg0.getIntervalType().toLowerCase();
			switch (timeUnit) {
				case "year":
				case "month":
					sqLtype = SQLtype.YM_INTERVAL;
					break;
				case "day":
				case "hour":
				case "minute":
				case "second":
					sqLtype = SQLtype.DT_INTERVAL;
					break;
				default:
					sqlExceptions.add(new SQLexception("Error - "
							+ "unknown time unit " + timeUnit + ". "
							+ "Allowed units are year, month, "
							+ "day, hour, minute, second."));
			}
		}
		return sqLtype;
	}
	/**
	 * Adds given number of months to a date, represented
	 * according to Unix time format.
	 *
	 * @param dateSecs	seconds since January 1st 1970
	 * @param months	number of months to add (can be negative)
	 * @return			seconds since January 1st 1970 after addition
	 */
	public static int addMonths(int dateSecs, int months) {
		Calendar calendar = Calendar.getInstance();
		try {
			calendar.setTimeInMillis((long)dateSecs * (long)1000);
			calendar.add(Calendar.MONTH, months);
		} catch (Exception e) {
			System.out.println("dateSecs: " + dateSecs);
			System.out.println("months: " + months);
			System.out.println(calendar);
			e.printStackTrace();
		}
		return (int)(calendar.getTimeInMillis()/(long)1000);
	}

	/**
	 * Adds code to treat the addition or subtraction of a
	 * number of months from a date or timestamp value.
	 * Returns true iff the given expression is indeed
	 * of that type.
	 *
	 * @param arg0	expression potentially involving months arithmetic
	 * @return		true if code for expression was added
	 */
	boolean treatAsMonthArithmetic(BinaryExpression arg0) {
		Expression left = arg0.getLeftExpression();
		Expression right = arg0.getRightExpression();

		SQLtype leftType = getDateType(left);
		SQLtype rightType = getDateType(right);
		boolean leftDate = dateYMintervalOp(leftType, rightType);
		boolean rightDate = dateYMintervalOp(rightType, leftType);

		if (leftDate || rightDate) {
			// Swap input parameters if required
			if (rightDate) {
				right = arg0.getLeftExpression();
				left = arg0.getRightExpression();
			}

			left.accept(this);
			int leftConstant = extractedConstants.pop();
			right.accept(this);
			int rightConstant = extractedConstants.pop();

			// Multiply number of months by -1 for subtraction
			if (arg0 instanceof Subtraction) {
				rightConstant = rightConstant * -1;
			}
			extractedConstants.push(addMonths(leftConstant, rightConstant));
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void visit(Addition addition) {
		if (!treatAsMonthArithmetic(addition)) {
			addition.getLeftExpression().accept(this);
			int c1 = extractedConstants.pop();
			addition.getRightExpression().accept(this);
			int c2 = extractedConstants.pop();
			extractedConstants.push(c1 + c2);
		}
	}

	@Override
	public void visit(Division division) {
		division.getLeftExpression().accept(this);
		int c1 = extractedConstants.pop();
		division.getRightExpression().accept(this);
		int c2 = extractedConstants.pop();
		extractedConstants.push(c1 / c2);
	}

	@Override
	public void visit(Multiplication multiplication) {
		multiplication.getLeftExpression().accept(this);
		int c1 = extractedConstants.pop();
		multiplication.getRightExpression().accept(this);
		int c2 = extractedConstants.pop();
		extractedConstants.push(c1 * c2);
	}

	@Override
	public void visit(Subtraction subtraction) {
		if (!treatAsMonthArithmetic(subtraction)) {
			subtraction.getLeftExpression().accept(this);
			int c1 = extractedConstants.pop();
			subtraction.getRightExpression().accept(this);
			int c2 = extractedConstants.pop();
			extractedConstants.push(c1 - c2);
		}
	}

	@Override
	public void visit(CastExpression castExpression) {
		// Get source and target type
		IntArrayList rows = new IntArrayList();
		rows.add(Integer.MAX_VALUE - 1);
		qualifyingRows.push(rows);
	}
}