package me.fleey.futon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ExampleUnitTest : StringSpec(
  {
    "addition is correct" {
      (2 + 2) shouldBe 4
    }
  },
)
