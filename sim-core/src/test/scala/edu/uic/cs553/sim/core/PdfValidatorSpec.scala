package edu.uic.cs553.sim.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import edu.uic.cs553.sim.core.models.Pdf
import edu.uic.cs553.sim.core.validation.PdfValidator

class PdfValidatorSpec extends AnyFunSuite with Matchers:

  test("valid PDF with probabilities summing to 1.0 passes validation"):
    val pdf = Pdf(Map("PING" -> 0.5, "GOSSIP" -> 0.3, "WORK" -> 0.2))
    noException should be thrownBy PdfValidator.validate(pdf)

  test("PDF whose probabilities sum to less than 1.0 fails validation"):
    val pdf = Pdf(Map("PING" -> 0.4, "GOSSIP" -> 0.3))
    an [IllegalArgumentException] should be thrownBy PdfValidator.validate(pdf)

  test("PDF whose probabilities sum to more than 1.0 fails validation"):
    val pdf = Pdf(Map("PING" -> 0.6, "GOSSIP" -> 0.6))
    an [IllegalArgumentException] should be thrownBy PdfValidator.validate(pdf)

  test("PDF with a negative probability fails validation"):
    val pdf = Pdf(Map("PING" -> 1.1, "GOSSIP" -> -0.1))
    an [IllegalArgumentException] should be thrownBy PdfValidator.validate(pdf)

  test("single-entry PDF with probability 1.0 passes validation"):
    val pdf = Pdf(Map("WORK" -> 1.0))
    noException should be thrownBy PdfValidator.validate(pdf)
