package domain

import munit.FunSuite

class SlotNoSpec extends FunSuite {

  test("1〜9 のみ作成できる") {
  assertEquals(SlotNo.from(1).map(_.value), Some(1))
  assertEquals(SlotNo.from(9).map(_.value), Some(9))
  assertEquals(SlotNo.from(0), None)
  assertEquals(SlotNo.from(10), None)
  }
}
