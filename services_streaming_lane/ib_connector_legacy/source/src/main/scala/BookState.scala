// File: src/main/scala/BookState.scala
package src.main.scala

/** 
  * An in-memory, cache-friendly L2 order book for a single instrument and fixed depth.
  *
  * The book stores per-(side,level) tuples (price, size, last_updated_ms) in a compact
  * 2D table to minimize allocations and maximize locality.
  *
  * Sides:
  *   - 0 = ask
  *   - 1 = bid
  *
  * Depth semantics:
  *   - Levels are indexed from 0 to mxDepth-1 per side.
  *   - INSERT shifts deeper levels outward (dropping the tail).
  *   - DELETE shifts deeper levels inward and clears the tail.
  *   - UPDATE overwrites the specified level in-place (no shifting).
  *
  * Invariants enforced after INSERT/DELETE:
  *   - Monotonic prices per side (asks non-decreasing, bids non-increasing).
  *   - No "holes": once a zero price appears on a side, all deeper levels must be zero.
  *
  * @param mxDepth maximum number of levels per side; must be > 0
  * @note Timestamps are stored as Double (epochMillis.toDouble) to match the table type.
  */
final class BookState(mxDepth: Int) {
	require(mxDepth > 0, "mxDepth must be > 0")

	// Row indices
	private val ROW_SIDE    = 0
	private val ROW_LEVEL   = 1
	private val ROW_PRICE   = 2
	private val ROW_SIZE    = 3
	private val ROW_UPDATED = 4

	// 2D table: rows = [side, level, price, size, last_updated_ms], cols = mxDepth * 2 (per side)
	// Store timestamps as Double (epochMillis.toDouble) to fit the table type.
    // for the sake of latency we use a fast data structure
	val table: Array[Array[Double]] = Array.ofDim[Double](5, mxDepth * 2)

	// ---- helpers ----

	/** 
	  * Compute the column index for a (side, position) pair.
	  *
	  * @param side  side identifier (0=ask, 1=bid)
	  * @param position level index in [0, mxDepth)
	  * @return column index into the backing table
	  * @throws IllegalArgumentException if side or position are out of allowed range
	  */
	private def colIndex(side: Int, position: Int): Int = {
		require(side == 0 || side == 1, s"side must be 0 (ask) or 1 (bid), got $side")
		require(position >= 0 && position < mxDepth, s"position out of bounds: $position (depth=$mxDepth)")
		side * mxDepth + position
	}

	/** 
	  * Copy one column's price/size/timestamp into another, setting the destination's side/level.
	  *
	  * @param src  source column index
	  * @param dst  destination column index
	  * @param side destination side (0=ask, 1=bid)
	  * @param level destination level index
	  */
	private def copyCol(src: Int, dst: Int, side: Int, level: Int): Unit = {
		table(ROW_PRICE)(dst)   = table(ROW_PRICE)(src)
		table(ROW_SIZE)(dst)    = table(ROW_SIZE)(src)
		table(ROW_UPDATED)(dst) = table(ROW_UPDATED)(src)
		table(ROW_SIDE)(dst)    = side.toDouble
		table(ROW_LEVEL)(dst)   = level.toDouble
	}

	/** 
	  * Clear a column to zeros while recording its side/level coordinates.
	  *
	  * @param col   column index to clear
	  * @param side  side (0=ask, 1=bid)
	  * @param level level index
	  */
	private def clearCol(col: Int, side: Int, level: Int): Unit = {
		table(ROW_PRICE)(col)   = 0.0
		table(ROW_SIZE)(col)    = 0.0
		table(ROW_UPDATED)(col) = 0.0
		table(ROW_SIDE)(col)    = side.toDouble
		table(ROW_LEVEL)(col)   = level.toDouble
	}

	/** 
	  * Set a column with price/size/timestamp and coordinates.
	  *
	  * @param col      target column index
	  * @param side     side (0=ask, 1=bid)
	  * @param level    level index
	  * @param price    non-negative price
	  * @param size     non-negative size
	  * @param tsMillis event/update time in epoch milliseconds
	  */
	private def setCol(col: Int, side: Int, level: Int, price: Double, size: Double, tsMillis: Long): Unit = {
		table(ROW_PRICE)(col)   = price
		table(ROW_SIZE)(col)    = size
		table(ROW_UPDATED)(col) = tsMillis.toDouble
		table(ROW_SIDE)(col)    = side.toDouble
		table(ROW_LEVEL)(col)   = level.toDouble
	}

	/** 
	  * Collect (side,level,price,size,timestamp) tuples for all levels starting at a given level.
	  *
	  * @param side        side (0=ask, 1=bid)
	  * @param startLevel  first level to include (inclusive)
	  * @return list of tuples for levels startLevel..mxDepth-1
	  */
	private def collectChangedFrom(side: Int, startLevel: Int): List[(Int, Int, Double, Double, Long)] = {
		(startLevel until mxDepth).map { lvl =>
			val c = colIndex(side, lvl)
			(side, lvl, table(ROW_PRICE)(c), table(ROW_SIZE)(c), table(ROW_UPDATED)(c).toLong)
		}.toList
	}

	/** 
	  * Ensure price monotonicity per side across contiguous non-zero levels.
	  *
	  * Rules:
	  *  - bid (side=1): price(i-1) ≥ price(i)
	  *  - ask (side=0): price(i-1) ≤ price(i)
	  *
	  * @param side side to validate (0=ask, 1=bid)
	  * @throws IllegalArgumentException if the monotonicity constraint is violated
	  */
	private def requireMonotonic(side: Int): Unit = {
		var i = 1
		while (i < mxDepth) {
			val pPrev = table(ROW_PRICE)(colIndex(side, i - 1))
			val pCurr = table(ROW_PRICE)(colIndex(side, i))
			if (pPrev > 0.0 && pCurr > 0.0) {
				if (side == 1) require(pPrev >= pCurr, s"bid not monotonic: price(${i - 1})=$pPrev < price($i)=$pCurr")
				else            require(pPrev <= pCurr, s"ask not monotonic: price(${i - 1})=$pPrev > price($i)=$pCurr")
			}
			i += 1
		}
	}

	/** 
	  * Ensure there are no holes on a side (only trailing zeros allowed).
	  *
	  * Once a zero price is encountered, all deeper levels on that side must
	  * have price==0 as well.
	  *
	  * @param side side to validate (0=ask, 1=bid)
	  * @throws IllegalArgumentException if a hole is detected
	  */
	private def requireNoHoles(side: Int): Unit = {
		var seenZero = false
		var i = 0
		while (i < mxDepth) {
			val p = table(ROW_PRICE)(colIndex(side, i))
			if (p == 0.0) seenZero = true
			else if (seenZero) require(false, s"$side side has a hole at level $i (non-zero after zero)")
			i += 1
		}
	}

	// ---- IB L2 operations ----
	// side: 0=ask, 1=bid

	/** 
	  * UPDATE at (side, position): overwrite price/size without shifting levels.
	  *
	  * Preconditions:
	  *  - side ∈ {0,1}
	  *  - 0 ≤ position < mxDepth
	  *  - price ≥ 0, size ≥ 0
	  *
	  * @param side      0=ask, 1=bid
	  * @param position  level index
	  * @param price     non-negative price
	  * @param size      non-negative size
	  * @param tsMillis  event/update time in epoch milliseconds
	  * @return a single tuple (side, level, price, size, ts_updated) reflecting the applied change
	  */
	def update(side: Int, position: Int, price: Double, size: Double, tsMillis: Long): List[(Int, Int, Double, Double, Long)] = {
		require(price >= 0.0, s"price must be >= 0, got $price")
		require(size  >= 0.0, s"size must be >= 0, got $size")
		require(side == 0 || side == 1, s"side must be 0 (ask) or 1 (bid), got $side")
		val col = colIndex(side, position)
		setCol(col, side, position, price, size, tsMillis)
		List((side, position, price, size, tsMillis))
	}

	/** 
	  * INSERT at (side, position): shift deeper levels down by one (dropping the tail),
	  * then set the new level at the given position.
	  *
	  * Preconditions:
	  *  - side ∈ {0,1}
	  *  - 0 ≤ position < mxDepth
	  *  - price ≥ 0, size ≥ 0
	  *
	  * Post-conditions:
	  *  - Enforces monotonic prices and no-holes invariants.
	  *
	  * @param side      0=ask, 1=bid
	  * @param position  insertion level index
	  * @param price     non-negative price
	  * @param size      non-negative size
	  * @param tsMillis  event/update time in epoch milliseconds
	  * @return tuples for all changed rows from `position` to `mxDepth-1`
	  */
	def insert(side: Int, position: Int, price: Double, size: Double, tsMillis: Long): List[(Int, Int, Double, Double, Long)] = {
		require(side == 0 || side == 1, s"side must be 0 (ask) or 1 (bid), got $side")
		require(position >= 0 && position < mxDepth, s"position out of bounds: $position (depth=$mxDepth)")
		require(price >= 0.0, s"price must be >= 0, got $price")
		require(size  >= 0.0, s"size must be >= 0, got $size")

		// shift deeper levels down by 1, drop last
		var level = mxDepth - 1
		while (level > position) {
			val srcCol = colIndex(side, level - 1)
			val dstCol = colIndex(side, level)
			copyCol(srcCol, dstCol, side, level)
			level -= 1
		}

		// insert new level
		val col = colIndex(side, position)
		setCol(col, side, position, price, size, tsMillis)

		// invariants
		requireMonotonic(side)
		requireNoHoles(side)

		// changed rows
		collectChangedFrom(side, position)
	}

	/** 
	  * DELETE at (side, position): shift deeper levels up by one and clear the tail.
	  *
	  * Preconditions:
	  *  - side ∈ {0,1}
	  *  - 0 ≤ position < mxDepth
	  *
	  * Post-conditions:
	  *  - Enforces monotonic prices and no-holes invariants.
	  *
	  * @param side      0=ask, 1=bid
	  * @param position  deletion level index
	  * @param tsMillis  event/update time in epoch milliseconds (applied to shifted rows via copy)
	  * @return tuples for all changed rows from `position` to `mxDepth-1`
	  */
	def delete(side: Int, position: Int, tsMillis: Long): List[(Int, Int, Double, Double, Long)] = {
		require(side == 0 || side == 1, s"side must be 0 (ask) or 1 (bid), got $side")
		require(position >= 0 && position < mxDepth, s"position out of bounds: $position (depth=$mxDepth)")

		// shift deeper levels UP by 1
		var level = position
		while (level < mxDepth - 1) {
			val srcCol = colIndex(side, level + 1)
			val dstCol = colIndex(side, level)
			copyCol(srcCol, dstCol, side, level)
			level += 1
		}

		// clear the last level after shift
		val tailCol = colIndex(side, mxDepth - 1)
		clearCol(tailCol, side, mxDepth - 1)

		// invariants
		requireMonotonic(side)
		requireNoHoles(side)

		// changed rows
		collectChangedFrom(side, position)
	}
}
