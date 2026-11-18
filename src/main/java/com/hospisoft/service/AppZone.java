package com.hospisoft.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record AppZone(ZoneId zone) {

    public static AppZone systemDefault() {
        return new AppZone(ZoneId.systemDefault());
    }

    public OffsetDateTime at(LocalDate date, LocalTime time) {
        return LocalDateTime.of(date, time).atZone(zone).toOffsetDateTime();
    }

    public OffsetDateTime dayStart(LocalDate date) {
        return date.atStartOfDay(zone).toOffsetDateTime();
    }

    public OffsetDateTime dayEnd(LocalDate date) {
        return date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }

    public LocalDate dateOf(OffsetDateTime instant) {
        return instant.atZoneSameInstant(zone).toLocalDate();
    }

    public LocalTime timeOf(OffsetDateTime instant) {
        return instant.atZoneSameInstant(zone).toLocalTime();
    }
}
