package com.dimitriazzarone.chatgptmessenger;

import java.io.*;

/** Copies provider streams to disk with constant memory and no partial output. */
public final class DanAttachmentCopy {
    private DanAttachmentCopy() {}
    public static long copy(InputStream source, File destination, long limit) throws IOException {
        File partial = new File(destination.getPath() + ".part");
        boolean complete = false;
        try {
            long total = 0;
            try (OutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Operazione annullata");
                    if (count == 0) continue;
                    if (count > limit - total) throw new IOException("Spazio di archiviazione insufficiente per la copia temporanea");
                    output.write(buffer, 0, count);
                    total += count;
                }
            }
            if (!partial.renameTo(destination)) throw new IOException("Copia temporanea non disponibile");
            complete = true;
            return total;
        } finally {
            if (!complete) partial.delete();
        }
    }
}
