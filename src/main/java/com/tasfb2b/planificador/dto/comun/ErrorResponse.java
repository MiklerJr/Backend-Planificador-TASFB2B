package com.tasfb2b.planificador.dto.comun;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private String error;
    private String mensaje;
    private int estado;
    private String timestamp;
    private String path;

    public static ErrorResponse of(int estado, String mensaje, String path) {
        return new ErrorResponse(mensaje, mensaje, estado, LocalDateTime.now().toString(), path);
    }
}
