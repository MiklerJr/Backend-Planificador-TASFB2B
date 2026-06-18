package com.tasfb2b.planificador.util;

public class ContinenteUtil {

    private ContinenteUtil() {}

    public static String desdeIcao(String icao) {
        if (icao == null || icao.isBlank()) return "UNKNOWN";
        return switch (Character.toUpperCase(icao.charAt(0))) {
            case 'S' -> "AM";
            case 'E', 'L', 'U' -> "EU";
            case 'O', 'V' -> "AS";
            default -> "UNKNOWN";
        };
    }
}
