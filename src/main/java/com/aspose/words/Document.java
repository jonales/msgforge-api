package com.aspose.words;

import java.io.InputStream;
import java.io.OutputStream;

/** Shim (ver package-info): só o necessário para o Cobalt carregar; nunca funciona. */
public class Document {

    public Document(InputStream input) {
        throw new UnsupportedOperationException("aspose-words não está disponível (shim)");
    }

    public int getPageCount() {
        throw new UnsupportedOperationException("aspose-words não está disponível (shim)");
    }

    public SaveOutputParameters save(OutputStream output, SaveOptions options) {
        throw new UnsupportedOperationException("aspose-words não está disponível (shim)");
    }
}
