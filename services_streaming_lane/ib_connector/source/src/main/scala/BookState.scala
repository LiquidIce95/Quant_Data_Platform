// File: src/main/scala/BookState.scala
package src.main.scala

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
	private def colIndex(side: Int, position: Int): Int = {
		require(side == 0 || side == 1, s"side must be 0 (ask) or 1 (bid), got $side")
		require(position >= 0 && position < mxDepth, s"position out of bounds: $position (depth=$mxDepth)")
		side * mxDepth + position
	}

	private def copyCol(src: Int, dst: Int, side: Int, level: Int): Unit = {
		table(ROW_PRICE)(dst)   = table(ROW_PRICE)(src)
		table(ROW_SIZE)(dst)    = table(ROW_SIZE)(src)
		table(ROW_UPDATED)(dst) = table(ROW_UPDATED)(src)
		table(ROW_SIDE)(dst)    = side.toDouble
		table(ROW_LEVEL)(dst)   = level.toDouble
	}

	private def clearCol(col: Int, side: Int, level: Int): Unit = {
		table(ROW_PRICE)(col)   = 0.0
		table(ROW_SIZE)(col)    = 0.0
		table(ROW_UPDATED)(col) = 0.0
		table(ROW_SIDE)(col)    = side.toDouble
		table(ROW_LEVEL)(col)   = level.toDouble
	}

	private def setCol(col: Int, side: Int, level: Int, price: Double, size: Double, tsMillis: Long): Unit = {
		table(ROW_PRICE)(col)   = price
		table(ROW_SIZE)(col)    = size
		table(ROW_UPDATED)(col) = tsMillis.toDouble
		table(ROW_SIDE)(col)    = side.toDouble
		table(ROW_LEVEL)(col)   = level.toDouble
	}

	private def collectChangedFrom(side: Int, startLevel: Int): List[(Int, Int, Double, Double, Long)] = {
		(startLevel until mxDepth).map { lvl =>
			val c = colIndex(side, lvl)
			(side, lvl, table(ROW_PRICE)(c), table(ROW_SIZE)(c), table(ROW_UPDATED)(c).toLong)
		}.toList
	}

	/** Require monotonic prices per side:
	  *  - bid (side=1): price(i-1) ≥ price(i)
	  *  - ask (side=0): price(i-1) ≤ price(i)
	  * Only compares contiguous non-zero priced levels.
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

	/** Require no holes:
	  * Only the trailing levels may have price==0.0.
	  * Once a zero price is seen, all deeper levels must be zero price as well.
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

	/** UPDATE at (side, position): overwrite price/size; do not shift.
	  *
	  * Preconditions:
	  *  - side ∈ {0,1}
	  *  - 0 ≤ position < mxDepth
	  *  - price ≥ 0, size ≥ 0
	  *
	  * Returns:
	  *  - Single tuple (side, level, price, size, ts_updated)
	  */
	def update(side: Int, position: Int, price: Double, size: Double, tsMillis: Long): List[(Int, Int, Double, Double, Long)] = {
		require(price >= 0.0, s"price must be >= 0, got $price")
		require(size  >= 0.0, s"size must be >= 0, got $size")
		require(side == 0 || side == 1, s"side must be 0 (ask) or 1 (bid), got $side")
		val col = colIndex(side, position)
		setCol(col, side, position, price, size, tsMillis)
		List((side, position, price, size, tsMillis))
	}

	/** INSERT at (side, position): shift deeper levels down by 1 (dropping the tail), then set the new level.
	  *
	  * Preconditions:
	  *  - side ∈ {0,1}
	  *  - 0 ≤ position < mxDepth
	  *  - price ≥ 0, size ≥ 0
	  *
	  * Post-checks (common invariants):
	  *  - Monotonic prices per side
	  *  - No holes (only trailing zeros allowed)
	  *
	  * Returns:
	  *  - Tuples for changed rows (levels position..mxDepth-1)
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

	/** DELETE at (side, position): shift deeper levels up by 1, then clear the tail.
	  *
	  * Preconditions:
	  *  - side ∈ {0,1}
	  *  - 0 ≤ position < mxDepth
	  *
	  * Post-checks (common invariants):
	  *  - Monotonic prices per side
	  *  - No holes (only trailing zeros allowed)
	  *
	  * Returns:
	  *  - Tuples for changed rows (levels position..mxDepth-1)
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
