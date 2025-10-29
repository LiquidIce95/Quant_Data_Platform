// File: src/test/scala/TestBookState.scala
package src.main.scala
import org.scalatest.funsuite.AnyFunSuite


class TestBookState extends AnyFunSuite {

	// -------- helpers (tests only) --------
	private def zeros(n: Int): Seq[(Double, Double, Long)] =
		Seq.fill(n)((0.0, 0.0, 0L))

	private def expectSide(b: BookState, side: Int, expected: Seq[(Double, Double, Long)]): Unit = {
		val mx = expected.length
		for (lvl <- 0 until mx) {
			val col = side * mx + lvl
			val (expP, expS, expT) = expected(lvl)
			assert(b.table(2)(col) == expP, s"price mismatch at side=$side lvl=$lvl")
			assert(b.table(3)(col) == expS, s"size  mismatch at side=$side lvl=$lvl")
			assert(b.table(4)(col).toLong == expT, s"ts    mismatch at side=$side lvl=$lvl")
			// if a cell is non-zero (or stamped), assert side/level metadata too
			if (expP != 0.0 || expS != 0.0 || expT != 0L) {
				assert(b.table(0)(col) == side.toDouble, s"side meta mismatch at side=$side lvl=$lvl")
				assert(b.table(1)(col) == lvl.toDouble,  s"level meta mismatch at side=$side lvl=$lvl")
			}
		}
	}

	test("update: writes a single level and returns one tuple") {
		val b = new BookState(4)
		val out = b.update(side = 0, position = 0, price = 10.0, size = 1.5, tsMillis = 1000L)
		assert(out == List((0, 0, 10.0, 1.5, 1000L)))

		expectSide(b, 0, Seq((10.0, 1.5, 1000L), (0.0, 0.0, 0L), (0.0, 0.0, 0L), (0.0, 0.0, 0L)))
		expectSide(b, 1, zeros(4))
	}

	test("update: rejects negative price") {
		val b = new BookState(3)
		intercept[IllegalArgumentException] { b.update(0, 0, -1.0, 1.0, 1L) }
		expectSide(b, 0, zeros(3))
		expectSide(b, 1, zeros(3))
	}

	test("update: rejects negative size") {
		val b = new BookState(3)
		intercept[IllegalArgumentException] { b.update(1, 0, 1.0, -5.0, 1L) }
		expectSide(b, 0, zeros(3))
		expectSide(b, 1, zeros(3))
	}

	test("update: rejects invalid side") {
		val b = new BookState(3)
		intercept[IllegalArgumentException] { b.update(2, 0, 1.0, 1.0, 1L) }
		expectSide(b, 0, zeros(3))
		expectSide(b, 1, zeros(3))
	}

	test("update: rejects out-of-bounds position") {
		val b = new BookState(2)
		intercept[IllegalArgumentException] { b.update(0, 2, 1.0, 1.0, 1L) }
		expectSide(b, 0, zeros(2))
		expectSide(b, 1, zeros(2))
	}

	test("insert(ask): shifts deeper levels down and returns changed rows") {
		val b = new BookState(4)
		b.update(0, 0, 10.0, 1.0, 1L)
		b.update(0, 1, 11.0, 2.0, 2L)
		val out = b.insert(0, 1, 10.5, 1.5, 3L)
		// changed rows: 1..3
		assert(out.length == 3)
		assert(out.head._2 == 1)   // level
		assert(out.head._3 == 10.5) // price at inserted level

		expectSide(b, 0, Seq(
			(10.0, 1.0, 1L),
			(10.5, 1.5, 3L),
			(11.0, 2.0, 2L),
			(0.0,  0.0,  0L)
		))
		expectSide(b, 1, zeros(4))
	}

	test("ask(mxDepth=2): insert at 0 then at 1, then at 0 again (check every step)") {
		val b = new BookState(2)

		val o1 = b.insert(0, 0, 10.0, 1.0, 1L)
		assert(o1 == List((0, 0, 10.0, 1.0, 1L), (0, 1, 0.0, 0.0, 0L)))
		expectSide(b, 0, Seq((10.0, 1.0, 1L), (0.0, 0.0, 0L)))
		expectSide(b, 1, zeros(2))

		val o2 = b.insert(0, 1, 10.1, 1.2, 2L)
		assert(o2 == List((0, 1, 10.1, 1.2, 2L)))
		expectSide(b, 0, Seq((10.0, 1.0, 1L), (10.1, 1.2, 2L)))
		expectSide(b, 1, zeros(2))

		val o3 = b.insert(0, 0, 9.9, 1.5, 3L)
		assert(o3 == List((0, 0, 9.9, 1.5, 3L), (0, 1, 10.0, 1.0, 1L)))
		expectSide(b, 0, Seq((9.9, 1.5, 3L), (10.0, 1.0, 1L)))
		expectSide(b, 1, zeros(2))
	}

	test("bid(mxDepth=2): insert at 0 then at 1, then at 0 again (check every step)") {
		val b = new BookState(2)

		val o1 = b.insert(1, 0, 100.0, 1.0, 1L)
		assert(o1 == List((1, 0, 100.0, 1.0, 1L), (1, 1, 0.0, 0.0, 0L)))
		expectSide(b, 1, Seq((100.0, 1.0, 1L), (0.0, 0.0, 0L)))
		expectSide(b, 0, zeros(2))

		val o2 = b.insert(1, 1, 99.8, 2.0, 2L)
		assert(o2 == List((1, 1, 99.8, 2.0, 2L)))
		expectSide(b, 1, Seq((100.0, 1.0, 1L), (99.8, 2.0, 2L)))
		expectSide(b, 0, zeros(2))

		val o3 = b.insert(1, 0, 100.5, 1.1, 3L)
		assert(o3 == List((1, 0, 100.5, 1.1, 3L), (1, 1, 100.0, 1.0, 1L)))
		expectSide(b, 1, Seq((100.5, 1.1, 3L), (100.0, 1.0, 1L)))
		expectSide(b, 0, zeros(2))
	}

	test("ask(mxDepth=2): inserting non-decreasing is required (violation throws)") {
		val b = new BookState(2)
		b.insert(0, 0, 10.0, 1.0, 1L)
		// pre-state assertions
		expectSide(b, 0, Seq((10.0, 1.0, 1L), (0.0, 0.0, 0L)))
		expectSide(b, 1, zeros(2))
		intercept[IllegalArgumentException] { b.insert(0, 1, 9.9, 1.0, 2L) }
		
	}

	test("bid(mxDepth=2): inserting non-increasing is required (violation throws)") {
		val b = new BookState(2)
		b.insert(1, 0, 100.0, 1.0, 1L)
		// pre-state assertions
		expectSide(b, 1, Seq((100.0, 1.0, 1L), (0.0, 0.0, 0L)))
		expectSide(b, 0, zeros(2))
		intercept[IllegalArgumentException] { b.insert(1, 1, 100.6, 1.0, 2L) }
		
	}

	test("insert(bid): maintains non-increasing prices") {
		val b = new BookState(3)
		b.update(1, 0, 101.0, 1.0, 1L)
		val out = b.insert(1, 1, 100.0, 1.0, 2L)
		assert(out.map(_._3) == List(100.0, 0.0)) // level1=100, level2=0
		expectSide(b, 1, Seq((101.0, 1.0, 1L), (100.0, 1.0, 2L), (0.0, 0.0, 0L)))
		expectSide(b, 0, zeros(3))
	}

	test("insert(ask): violates monotonicity -> throws") {
		val b = new BookState(3)
		b.update(0, 0, 10.0, 1.0, 1L)
		// pre-state
		expectSide(b, 0, Seq((10.0, 1.0, 1L), (0.0, 0.0, 0L), (0.0, 0.0, 0L)))
		expectSide(b, 1, zeros(3))
		intercept[IllegalArgumentException] { b.insert(0, 1, 9.9, 1.0, 2L) }
	}

	test("insert(bid): violates monotonicity -> throws") {
		val b = new BookState(3)
		b.update(1, 0, 100.0, 1.0, 1L)
		expectSide(b, 1, Seq((100.0, 1.0, 1L), (0.0, 0.0, 0L), (0.0, 0.0, 0L)))
		expectSide(b, 0, zeros(3))
		intercept[IllegalArgumentException] { b.insert(1, 1, 101.0, 1.0, 2L) }
	}

	test("insert: rejects invalid side and position and negatives") {
		val b = new BookState(2)
		intercept[IllegalArgumentException] { b.insert(3, 0, 1.0, 1.0, 1L) }
		intercept[IllegalArgumentException] { b.insert(0, 2, 1.0, 1.0, 1L) }
		intercept[IllegalArgumentException] { b.insert(0, 0, -1.0, 1.0, 1L) }
		intercept[IllegalArgumentException] { b.insert(0, 0, 1.0, -1.0, 1L) }
		expectSide(b, 0, zeros(2))
		expectSide(b, 1, zeros(2))
	}

	test("delete: shifts levels up and clears tail, returns changed rows") {
		val b = new BookState(3)
		b.update(0, 0, 10.0, 1.0, 1L)
		b.update(0, 1, 11.0, 1.0, 2L)
		val out = b.delete(0, 0, 3L)
		assert(out.length == 3)
		val l0 = out.head; val l1 = out(1); val l2 = out(2)
		assert(l0._3 == 11.0)
		assert(l2._3 == 0.0)

		expectSide(b, 0, Seq((11.0, 1.0, 2L), (0.0, 0.0, 0L), (0.0, 0.0, 0L)))
		expectSide(b, 1, zeros(3))
	}

	test("delete on last level: only clears the last and returns one row") {
		val b = new BookState(2)
		b.update(1, 0, 100.0, 1.0, 1L)
		b.update(1, 1, 99.0,  1.0, 2L)
		val out = b.delete(1, 1, 3L)
		assert(out.length == 1)
		assert(out.head == (1, 1, 0.0, 0.0, 0L))

		expectSide(b, 1, Seq((100.0, 1.0, 1L), (0.0, 0.0, 0L)))
		expectSide(b, 0, zeros(2))
	}

	test("delete(bid): violating monotonicity after shift throws") {
		val b = new BookState(3)
		b.update(1, 0, 100.0, 1.0, 1L)
		b.update(1, 1,  90.0, 1.0, 2L)
		b.update(1, 2, 110.0, 1.0, 3L) // bad deeper price
		// pre-state
		expectSide(b, 1, Seq((100.0, 1.0, 1L), (90.0, 1.0, 2L), (110.0, 1.0, 3L)))
		expectSide(b, 0, zeros(3))
		intercept[IllegalArgumentException] { b.delete(1, 1, 4L) }
		// after delete throws, state is mutated by our implementation; assert new (shifted) state present
		expectSide(b, 1, Seq((100.0, 1.0, 1L), (110.0, 1.0, 3L), (0.0, 0.0, 0L)))
		expectSide(b, 0, zeros(3))
	}

	test("no holes rule enforced on insert if a hole pre-exists") {
		val b = new BookState(4)
		b.update(0, 1, 11.0, 1.0, 1L) // hole
		expectSide(b, 0, Seq((0.0, 0.0, 0L), (11.0, 1.0, 1L), (0.0, 0.0, 0L), (0.0, 0.0, 0L)))
		expectSide(b, 1, zeros(4))
		intercept[IllegalArgumentException] { b.insert(0, 0, 10.0, 1.0, 2L) }
		
	}

	test("no holes rule enforced on delete if a hole pre-exists") {
		val b = new BookState(4)
		b.update(1, 2, 95.0, 1.0, 1L) // hole at 0..1
		expectSide(b, 1, Seq((0.0, 0.0, 0L), (0.0, 0.0, 0L), (95.0, 1.0, 1L), (0.0, 0.0, 0L)))
		expectSide(b, 0, zeros(4))
		intercept[IllegalArgumentException] { b.delete(1, 0, 2L) }
		// After our delete, invariants are checked; since it throws after shifting, assert mutated state:
		expectSide(b, 1, Seq((0.0, 0.0, 0L), (95.0, 1.0, 1L), (0.0, 0.0, 0L), (0.0, 0.0, 0L)))
		expectSide(b, 0, zeros(4))
	}

	test("ask sequence: monotonic non-decreasing across multiple inserts") {
		val b = new BookState(5)
		b.insert(0, 0, 10.0, 1.0, 1L)
		b.insert(0, 1, 10.1, 1.0, 2L)
		b.insert(0, 2, 10.2, 1.0, 3L)
		val last = b.insert(0, 2, 10.15, 1.0, 4L)
		assert(last.head._3 == 10.15)
		expectSide(b, 0, Seq((10.0, 1.0, 1L), (10.1, 1.0, 2L), (10.15, 1.0, 4L), (10.2, 1.0, 3L), (0.0, 0.0, 0L)))
		expectSide(b, 1, zeros(5))
	}

	test("bid sequence: monotonic non-increasing across multiple inserts") {
		val b = new BookState(5)
		b.insert(1, 0, 100.0, 1.0, 1L)
		b.insert(1, 1,  99.9, 1.0, 2L)
		b.insert(1, 2,  99.8, 1.0, 3L)
		val out = b.insert(1, 2, 99.85, 1.0, 4L)
		// Expect changed rows for levels 2..4: [99.85, 99.8, 0.0]
		assert(out.length == 3)
		assert(out(0)._3 == 99.85)
		assert(out(1)._3 == 99.8)
		assert(out(2)._3 == 0.0)
		expectSide(b, 1, Seq((100.0, 1.0, 1L), (99.9, 1.0, 2L), (99.85, 1.0, 4L), (99.8, 1.0, 3L), (0.0, 0.0, 0L)))
		expectSide(b, 0, zeros(5))
	}

	test("timestamps: insert copies timestamps on shifted rows, sets new at position") {
		val b = new BookState(3)
		b.update(0, 0, 10.0, 1.0, 100L)
		b.update(0, 1, 11.0, 1.0, 200L)
		val out = b.insert(0, 0, 9.9, 1.0, 300L)
		assert(out.head._5 == 300L)
		assert(out(1)._5 == 100L)
		expectSide(b, 0, Seq((9.9, 1.0, 300L), (10.0, 1.0, 100L), (11.0, 1.0, 200L)))
		expectSide(b, 1, zeros(3))
	}

	test("mxDepth=1: insert then delete works") {
		val b = new BookState(1)
		val o1 = b.insert(0, 0, 10.0, 1.0, 1L)
		assert(o1 == List((0, 0, 10.0, 1.0, 1L)))
		expectSide(b, 0, Seq((10.0, 1.0, 1L)))
		expectSide(b, 1, zeros(1))
		val o2 = b.delete(0, 0, 2L)
		assert(o2 == List((0, 0, 0.0, 0.0, 0L)))
		expectSide(b, 0, Seq((0.0, 0.0, 0L)))
		expectSide(b, 1, zeros(1))
	}

	test("insert at last position only affects that row") {
		val b = new BookState(3)
		b.update(0, 0, 10.0, 1.0, 1L)
		b.update(0, 1, 11.0, 1.0, 2L)
		val out = b.insert(0, 2, 11.5, 1.0, 3L)
		assert(out.length == 1)
		assert(out.head == (0, 2, 11.5, 1.0, 3L))
		expectSide(b, 0, Seq((10.0, 1.0, 1L), (11.0, 1.0, 2L), (11.5, 1.0, 3L)))
		expectSide(b, 1, zeros(3))
	}

	test("delete from middle compacts and keeps no holes") {
		val b = new BookState(4)
		b.update(0, 0, 10.0, 1.0, 1L)
		b.update(0, 1, 10.1, 1.0, 2L)
		b.update(0, 2, 10.2, 1.0, 3L)
		val out = b.delete(0, 1, 4L)
		assert(out.length == 3)
		assert(out.head._3 == 10.2)
		assert(out(1)._3 == 0.0)
		assert(out(2)._3 == 0.0)
		expectSide(b, 0, Seq((10.0, 1.0, 1L), (10.2, 1.0, 3L), (0.0, 0.0, 0L), (0.0, 0.0, 0L)))
		expectSide(b, 1, zeros(4))
	}
}
