package edu.uic.cs553.sim.core.validation

import edu.uic.cs553.sim.core.models.Pdf

object PdfValidator:

  def validate(pdf: Pdf, tolerance: Double = 1e-6): Unit =
    val total = pdf.probabilities.values.sum

    require(
      math.abs(total - 1.0) <= tolerance,
      s"Invalid PDF: probabilities must sum to 1.0 but got $total"
    )

    require(
      pdf.probabilities.values.forall(_ >= 0.0),
      "Invalid PDF: probabilities must be non-negative"
    )