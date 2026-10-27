package com.wbm.core.rvz;

import java.io.IOException;

/** Error al interpretar el contenido de un archivo RVZ (corrupto, formato no admitido, etc.). */
public class RvzUnpackException extends IOException {
    public RvzUnpackException(String msg) {
        super(msg);
    }

    public RvzUnpackException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
