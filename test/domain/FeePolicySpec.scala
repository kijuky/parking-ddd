package domain

import munit.FunSuite

class FeePolicySpec extends FunSuite {

  test("最初の5分は無料") {
  assertEquals(FeePolicy.calcFeeYen(1), 0)
  assertEquals(FeePolicy.calcFeeYen(5), 0)
  }

  test("6分で200円") {
  assertEquals(FeePolicy.calcFeeYen(6), 200)
  }

  test("30分までは200円") {
  assertEquals(FeePolicy.calcFeeYen(30), 200)
  }

  test("31分で400円に切り上げ") {
  assertEquals(FeePolicy.calcFeeYen(31), 400)
  }

  test("61分で600円に切り上げ") {
  assertEquals(FeePolicy.calcFeeYen(61), 600)
  }
}
