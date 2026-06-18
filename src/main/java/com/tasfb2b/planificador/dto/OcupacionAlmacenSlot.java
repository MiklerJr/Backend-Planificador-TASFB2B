package com.tasfb2b.planificador.dto;

import lombok.Data;

/**
 * Serie temporal de ocupación de almacén por SLOT de 60 minutos — la granularidad nativa del
 * modelo interno — para que el front actualice EN VIVO las maletas de cada almacén mientras
 * su reloj de animación avanza dentro del bloque. {@code hora} es el INICIO del slot en eje
 * UTC; {@code ocupacion} es el ACUMULADO vigente del slot (estadías commiteadas de todos los
 * bloques hasta este, incluida la espera en origen de envíos sin ruta del backlog).
 * La expone {@code GET /jobs/{id}/almacenes/serie?desde=N}.
 */
@Data
public class OcupacionAlmacenSlot {
    private String aeropuerto;
    /** Inicio del slot (ISO sin offset, eje UTC). El slot cubre [hora, hora+60min). */
    private String hora;
    private int capacidadMaxima;
    /** Maletas presentes a la vez en el almacén durante este slot (acumulado global). */
    private int ocupacion;
    private double porcentajeOcupacion;
    private String semaforo;
}
