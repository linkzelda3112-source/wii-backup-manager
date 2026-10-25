package com.wbm.core;

import java.io.IOException;

/** La imagen no es un disco de Wii que se pueda convertir. */
public class UnsupportedDiscException extends IOException {
    public UnsupportedDiscException(String msg) {
        super(msg);
    }
}
