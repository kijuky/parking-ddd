package domain

import munit.FunSuite

class MoneySpec extends FunSuite {

  test("validate は 0 以上なら Money を返す") {
    assertEquals(Money.validate(0), Right(Money.unsafe(0)))
    assertEquals(Money.validate(200), Right(Money.unsafe(200)))
  }

  test("validate は負数なら Left を返す") {
    assertEquals(Money.validate(-1), Left(InvalidMoney("money must be non-negative, but was -1")))
  }

  test("safeMinus は負値になる減算を Left で返す") {
    assertEquals(
      Money.unsafe(100).safeMinus(Money.unsafe(200)),
      Left(InvalidMoney("money must be non-negative, but was -100"))
    )
  }
}
