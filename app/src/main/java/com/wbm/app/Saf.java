package com.wbm.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import com.wbm.core.DiscInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/** Acceso a archivos mediante el Storage Access Framework (carpeta elegida por el usuario). */
final class Saf {
    private Saf() {}

    static final class Entry {
        final String docId;
        final Uri uri;
        final String name;
        final long size;
        final boolean dir;

        Entry(String docId, Uri uri, String name, long size, boolean dir) {
            this.docId = docId;
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.dir = dir;
        }
    }

    static List<Entry> list(ContentResolver cr, Uri tree, String parentDocId) {
        List<Entry> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
        String[] projection = {
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_SIZE,
                Document.COLUMN_MIME_TYPE
        };
        try (Cursor c = cr.query(children, projection, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? -1 : c.getLong(2);
                boolean dir = Document.MIME_TYPE_DIR.equals(c.getString(3));
                out.add(new Entry(id, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size, dir));
            }
        } catch (RuntimeException e) {
            // proveedor no disponible o carpeta ilegible: se trata como vacía
        }
        return out;
    }

    static Entry findChild(ContentResolver cr, Uri tree, String parentDocId, String name) {
        for (Entry e : list(cr, tree, parentDocId)) {
            if (e.name.equals(name)) return e;
        }
        return null;
    }

    static Entry ensureDir(ContentResolver cr, Uri tree, String parentDocId, String name) throws IOException {
        for (Entry e : list(cr, tree, parentDocId)) {
            if (e.dir && e.name.equals(name)) return e;
        }
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId);
        Uri created = DocumentsContract.createDocument(cr, parent, Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("No se pudo crear la carpeta «" + name + "».");
        String id = DocumentsContract.getDocumentId(created);
        return new Entry(id, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, -1, true);
    }

    /** Crea un archivo nuevo vacío; el llamador debe comprobar antes que no exista. */
    static Entry createFile(ContentResolver cr, Uri tree, String parentDocId, String name) throws IOException {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId);
        Uri created = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name);
        if (created == null) throw new IOException("No se pudo crear el archivo «" + name + "».");
        String id = DocumentsContract.getDocumentId(created);
        return new Entry(id, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, 0, false);
    }

    static void deleteQuiet(ContentResolver cr, Uri uri) {
        try {
            DocumentsContract.deleteDocument(cr, uri);
        } catch (Exception ignored) {
            // nada que hacer si no se puede borrar
        }
    }

    static FileChannel openRead(ContentResolver cr, Uri uri) throws IOException {
        ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("No se pudo abrir el archivo.");
        return new ParcelFileDescriptor.AutoCloseInputStream(pfd).getChannel();
    }

    static FileChannel openWrite(ContentResolver cr, Uri uri) throws IOException {
        ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw");
        if (pfd == null) throw new IOException("No se pudo abrir el archivo de destino.");
        return new ParcelFileDescriptor.AutoCloseOutputStream(pfd).getChannel();
    }

    static void readFully(FileChannel ch, long pos, byte[] b) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b);
        while (bb.hasRemaining()) {
            int r = ch.read(bb, pos + bb.position());
            if (r < 0) throw new IOException("Fin de archivo inesperado");
        }
    }

    /** Lee la cabecera del disco de una ISO o de un WBFS para mostrarla en la lista. */
    static DiscInfo readDiscInfo(ContentResolver cr, Uri uri, boolean wbfs) throws IOException {
        try (FileChannel ch = openRead(cr, uri)) {
            long pos = 0;
            if (wbfs) {
                byte[] head = new byte[16];
                readFully(ch, 0, head);
                if (head[0] != 'W' || head[1] != 'B' || head[2] != 'F' || head[3] != 'S') {
                    throw new IOException("No es WBFS");
                }
                int shift = head[8] & 0xFF;
                if (shift < 9 || shift > 16) throw new IOException("WBFS no válido");
                pos = 1L << shift;
            }
            byte[] h = new byte[0x100];
            readFully(ch, pos, h);
            return DiscInfo.parse(h);
        }
    }
}
