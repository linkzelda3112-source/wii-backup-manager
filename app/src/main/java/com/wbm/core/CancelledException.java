package com.wbm.core;

import java.io.IOException;

public class CancelledException extends IOException {
    public CancelledException() {
        super("Operación cancelada");
    }
}
