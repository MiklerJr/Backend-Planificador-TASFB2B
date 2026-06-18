package com.tasfb2b.planificador.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Converter
public class LocalDateTimeToTimeStringConverter
        implements AttributeConverter<LocalDateTime, String> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final LocalDate BASE = LocalDate.of(2026, 1, 1);

    @Override
    public String convertToDatabaseColumn(LocalDateTime attr) {
        if (attr == null) return null;
        return attr.toLocalTime().format(FMT);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return LocalDateTime.of(BASE, LocalTime.parse(dbData));
    }
}
