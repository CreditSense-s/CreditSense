package com.creditsense.api;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GstinValidatorTest {
  @Test void validates_a_known_gstin_and_rejects_checksum_changes() {
    assertTrue(ApiController.gstinValid("27AAPFU0939F1ZV"));
    assertFalse(ApiController.gstinValid("27AAPFU0939F1ZW"));
    assertFalse(ApiController.gstinValid("not-a-gstin"));
  }
}
