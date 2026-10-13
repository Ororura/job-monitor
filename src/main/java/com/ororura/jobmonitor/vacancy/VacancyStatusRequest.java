package com.ororura.jobmonitor.vacancy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record VacancyStatusRequest(VacancyStatus status) {

  // Parse names explicitly so Jackson cannot interpret numeric values as enum ordinals.
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public static VacancyStatusRequest fromJson(@JsonProperty("status") String status) {
    return new VacancyStatusRequest(status == null ? null : VacancyStatus.valueOf(status));
  }
}
