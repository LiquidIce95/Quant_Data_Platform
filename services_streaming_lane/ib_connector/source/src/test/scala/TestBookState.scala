// File: src/test/scala/TestBookState.scala
import org.scalatest.funsuite.AnyFunSuite
import src.main.scala.BookState

class TestBookState extends AnyFunSuite {

	test("update: writes a single level and returns one tuple") {
		val b = new BookState(4)
		val out = b.update(side = 0, position = 0, price = 10.0, size = 1.5, tsMillis = 1000L)
		assert(out == List((0, 0, 10.0, 1.5, 1000L)))
	}

	test("update: rejects negative price") {
		val b = new BookState(3)
		intercept[IllegalArgumentException] {
			b.update(0, 0, -1.0, 1.0, 1L)
		}
	}

	test("update: rejects negative size") {
		val b = new BookState(3)
		intercept[IllegalArgumentException] {
			b.update(1, 0, 1.0, -5.0, 1L)
		}
	}

	test("update: rejects invalid side") {
		val b = new BookState(3)
		intercept[IllegalArgumentException] {
			b.update(2, 0, 1.0, 1.0, 1L)
		}
	}

	test("update: rejects out-of-bounds position") {
		val b = new BookState(2)
		intercept[IllegalArgumentException] {
			b.update(0, 2, 1.0, 1.0, 1L)
		}
	}

	test("insert(ask): shifts deeper levels down and returns changed rows") {
		val b = new BookState(4)
		b.update(0, 0, 10.0, 1.0, 1L)
		b.update(0, 1, 11.0, 2.0, 2L)
		val out = b.insert(0, 1, 10.5, 1.5, 3L)
		// changed rows: 1..3
		assert(out.length == 3)
		assert(out.head._2 == 1) // level
		assert(out.head._3 == 10.5) // price at inserted level
        
	}
    	test("ask(mxDepth=2): insert at 0 then at 1, then at 0 again (check every step)") {
		val b = new BookState(2)

		// Step 1: insert ask at level 0
		val o1 = b.insert(0, 0, 10.0, 1.0, 1L)
		assert(o1 == List(
			(0, 0, 10.0, 1.0, 1L),
			(0, 1, 0.0,  0.0,  0L)
		))
		// Table: [10.0, 0.0]
		assert(b.table(2)(0*2+0) == 10.0)
		assert(b.table(2)(0*2+1) == 0.0)

		// Step 2: insert ask at level 1
		val o2 = b.insert(0, 1, 10.1, 1.2, 2L)
		assert(o2 == List(
			(0, 1, 10.1, 1.2, 2L)
		))
		// Table: [10.0, 10.1]
		assert(b.table(2)(0*2+0) == 10.0)
		assert(b.table(2)(0*2+1) == 10.1)

		// Step 3: insert ask at level 0 (shifts old L0 to L1)
		val o3 = b.insert(0, 0, 9.9, 1.5, 3L)
		assert(o3 == List(
			(0, 0, 9.9, 1.5, 3L),
			(0, 1, 10.0, 1.0, 1L)
		))
		// Table: [9.9, 10.0]
		assert(b.table(2)(0*2+0) == 9.9)
		assert(b.table(2)(0*2+1) == 10.0)
	}

	test("bid(mxDepth=2): insert at 0 then at 1, then at 0 again (check every step)") {
		val b = new BookState(2)

		// Step 1: insert bid at level 0
		val o1 = b.insert(1, 0, 100.0, 1.0, 1L)
		assert(o1 == List(
			(1, 0, 100.0, 1.0, 1L),
			(1, 1, 0.0,   0.0,  0L)
		))
		assert(b.table(2)(1*2+0) == 100.0)
		assert(b.table(2)(1*2+1) == 0.0)

		// Step 2: insert bid at level 1
		val o2 = b.insert(1, 1, 99.8, 2.0, 2L)
		assert(o2 == List(
			(1, 1, 99.8, 2.0, 2L)
		))
		assert(b.table(2)(1*2+0) == 100.0)
		assert(b.table(2)(1*2+1) == 99.8)

		// Step 3: insert bid at level 0 (shifts old L0 to L1)
		val o3 = b.insert(1, 0, 100.5, 1.1, 3L)
		assert(o3 == List(
			(1, 0, 100.5, 1.1, 3L),
			(1, 1, 100.0, 1.0, 1L)
		))
		assert(b.table(2)(1*2+0) == 100.5)
		assert(b.table(2)(1*2+1) == 100.0)
	}

	test("ask(mxDepth=2): inserting non-decreasing is required (violation throws)") {
		val b = new BookState(2)
		b.insert(0, 0, 10.0, 1.0, 1L)
		intercept[IllegalArgumentException] {
			// level1 must be >= level0; 9.9 breaks it
			b.insert(0, 1, 9.9, 1.0, 2L)
		}
	}

	test("bid(mxDepth=2): inserting non-increasing is required (violation throws)") {
		val b = new BookState(2)
		b.insert(1, 0, 100.0, 1.0, 1L)
		intercept[IllegalArgumentException] {
			// level1 must be <= level0; 100.6 breaks it
			b.insert(1, 1, 100.6, 1.0, 2L)
		}
	}

	test("insert(bid): maintains non-increasing prices") {
		val b = new BookState(3)
		b.update(1, 0, 101.0, 1.0, 1L)
		val out = b.insert(1, 1, 100.0, 1.0, 2L)
		assert(out.map(_._3) == List(100.0, 0.0)) // level1=100, level2=0
	}

	test("insert(ask): violates monotonicity -> throws") {
		val b = new BookState(3)
		b.update(0, 0, 10.0, 1.0, 1L)
		intercept[IllegalArgumentException] {
			// ask must be non-decreasing; inserting 9.9 at level 1 breaks it
			b.insert(0, 1, 9.9, 1.0, 2L)
		}
	}

	test("insert(bid): violates monotonicity -> throws") {
		val b = new BookState(3)
		b.update(1, 0, 100.0, 1.0, 1L)
		intercept[IllegalArgumentException] {
			// bid must be non-increasing; inserting 101 at level 1 breaks it
			b.insert(1, 1, 101.0, 1.0, 2L)
		}
	}

	test("insert: rejects invalid side and position and negatives") {
		val b = new BookState(2)
		intercept[IllegalArgumentException] { b.insert(3, 0, 1.0, 1.0, 1L) }
		intercept[IllegalArgumentException] { b.insert(0, 2, 1.0, 1.0, 1L) }
		intercept[IllegalArgumentException] { b.insert(0, 0, -1.0, 1.0, 1L) }
		intercept[IllegalArgumentException] { b.insert(0, 0, 1.0, -1.0, 1L) }
	}

	test("delete: shifts levels up and clears tail, returns changed rows") {
		val b = new BookState(3)
		b.update(0, 0, 10.0, 1.0, 1L)
		b.update(0, 1, 11.0, 1.0, 2L)
		val out = b.delete(0, 0, 3L)
		assert(out.length == 3) // levels 0..2 changed
		val l0 = out.head; val l1 = out(1); val l2 = out(2)
		assert(l0._3 == 11.0) // price moved up
		assert(l2._3 == 0.0)  // tail cleared
	}

	test("delete on last level: only clears the last and returns one row") {
		val b = new BookState(2)
		b.update(1, 0, 100.0, 1.0, 1L)
		b.update(1, 1, 99.0,  1.0, 2L)
		val out = b.delete(1, 1, 3L)
		assert(out.length == 1)
		assert(out.head == (1, 1, 0.0, 0.0, 0L)) // cleared row
	}

	test("delete(bid): violating monotonicity after shift throws") {
		val b = new BookState(3)
		// Set descending then make deeper level higher; delete will shift it up and violate
		b.update(1, 0, 100.0, 1.0, 1L)
		b.update(1, 1,  90.0, 1.0, 2L)
		b.update(1, 2, 110.0, 1.0, 3L) // bad deeper price
		intercept[IllegalArgumentException] {
			b.delete(1, 1, 4L) // shifts 110.0 to level1 -> breaks monotonic
		}
	}

	test("no holes rule enforced on insert if a hole pre-exists") {
		val b = new BookState(4)
		// Create a hole: level0 zero, level1 non-zero using update (update doesn't check holes)
		b.update(0, 1, 11.0, 1.0, 1L)
		intercept[IllegalArgumentException] {
			b.insert(0, 0, 10.0, 1.0, 2L) // triggers no-holes check
		}
	}

	test("no holes rule enforced on delete if a hole pre-exists") {
		val b = new BookState(4)
		b.update(1, 2, 95.0, 1.0, 1L) // hole at levels 0..1
		intercept[IllegalArgumentException] {
			b.delete(1, 0, 2L)
		}
	}

	test("ask sequence: monotonic non-decreasing across multiple inserts") {
		val b = new BookState(5)
		b.insert(0, 0, 10.0, 1.0, 1L)
		b.insert(0, 1, 10.1, 1.0, 2L)
		b.insert(0, 2, 10.2, 1.0, 3L)
		val last = b.insert(0, 2, 10.15, 1.0, 4L) // shift
		assert(last.head._3 == 10.15)
	}

    test("bid sequence: monotonic non-increasing across multiple inserts") {
        val b = new BookState(5)
        b.insert(1, 0, 100.0, 1.0, 1L)
        b.insert(1, 1,  99.9, 1.0, 2L)
        b.insert(1, 2,  99.8, 1.0, 3L)

        val out = b.insert(1, 2, 99.85, 1.0, 4L)

        // Expect changed rows for levels 2..4: [99.85, 99.8, 0.0]
        assert(out.length == 3)
        assert(out(0)._3 == 99.85) // price of first element (level 2, new)
        assert(out(1)._3 == 99.8)  // price of second element (level 3, shifted old level 2)
        assert(out(2)._3 == 0.0)   // price of third element (level 4, cleared tail)
    }


	test("timestamps: insert copies timestamps on shifted rows, sets new at position") {
		val b = new BookState(3)
		b.update(0, 0, 10.0, 1.0, 100L)
		b.update(0, 1, 11.0, 1.0, 200L)
		val out = b.insert(0, 0, 9.9, 1.0, 300L)
		// levels 0..2 changed, check ts at new level0 and shifted level1
		assert(out.head._5 == 300L) // new
		assert(out(1)._5 == 100L)   // old level0 moved to level1 keeps ts
	}

	test("mxDepth=1: insert then delete works") {
		val b = new BookState(1)
		val o1 = b.insert(0, 0, 10.0, 1.0, 1L)
		assert(o1 == List((0, 0, 10.0, 1.0, 1L)))
		val o2 = b.delete(0, 0, 2L)
		assert(o2 == List((0, 0, 0.0, 0.0, 0L)))
	}

	test("insert at last position only affects that row") {
		val b = new BookState(3)
		b.update(0, 0, 10.0, 1.0, 1L)
		b.update(0, 1, 11.0, 1.0, 2L)
		val out = b.insert(0, 2, 11.5, 1.0, 3L)
		assert(out.length == 1)
		assert(out.head == (0, 2, 11.5, 1.0, 3L))
	}

	test("delete from middle compacts and keeps no holes") {
		val b = new BookState(4)
		b.update(0, 0, 10.0, 1.0, 1L)
		b.update(0, 1, 10.1, 1.0, 2L)
		b.update(0, 2, 10.2, 1.0, 3L)
		val out = b.delete(0, 1, 4L)
		// Levels now: 0->10.0, 1->10.2, 2->0.0, 3->0.0
		assert(out.length == 3)
		assert(out.head._3 == 10.2) // new level1 price
		assert(out(1)._3 == 0.0)
		assert(out(2)._3 == 0.0)
	}
}
