package com.wbm.core;

/** Callback de progreso. Devuelve false para cancelar la operación. */
public interface Progress {
    boolean update(long done, long total);
}
