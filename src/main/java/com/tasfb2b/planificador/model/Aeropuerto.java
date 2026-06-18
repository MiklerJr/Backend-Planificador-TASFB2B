package com.tasfb2b.planificador.model;

import com.tasfb2b.planificador.util.ContinenteUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name="aeropuerto")
public class Aeropuerto {

    @NotBlank(message = "Al aeropuerto le corresponde una ciudad")
    @Column(nullable = false)
    private String ciudad;

    @NotBlank(message = "Al aeropuerto le corresponde un país")
    @Column(nullable = false)
    private String pais;

    @Column(nullable = false)
    private boolean activo = true;

    @Transient
    @EqualsAndHashCode.Exclude
    private String continente;

    @NotBlank(message = "El aeropuerto debe tener un codigo de identificación")
    @Id
    @Column(name = "icao", nullable = false, unique = true, length = 4)
    private String codigo;

    @NotNull(message = "El aeropuerto debe tener una offset de horario")
    @Column(name = "huso_horario", nullable = false)
    private Integer offsetHorario;

    @NotNull(message = "El aeropuerto debe tener registrado una capacidad de almacenaje")
    @Column(name = "capacidad_almacen", nullable = false)
    private Integer capacidad;

    @NotNull(message = "El aeropuerto debe indicar su latitud")
    @Column(nullable = false)
    private Double latitud;

    @NotNull(message = "El aeropuerto debe indicar su longitud")
    @Column(nullable = false)
    private Double longitud;

    public String getContinente() {
        if (continente == null && codigo != null) {
            return ContinenteUtil.desdeIcao(codigo);
        }
        return continente == null ? "UNKNOWN" : continente;
    }
}
