package com.aisys.dataset.service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Read standard UTF-8 ZIPs and legacy Chinese filename ZIPs without rewriting the archive. */
final class ZipArchives {
    private ZipArchives() {}

    static ZipFile open(Path path) throws IOException {
        try {
            return new ZipFile(path.toFile());
        } catch (ZipException utf8Error) {
            if (utf8Error.getMessage() == null || !utf8Error.getMessage().contains("bad entry name")) {
                throw utf8Error;
            }
            try {
                return new ZipFile(path.toFile(), Charset.forName("GB18030"));
            } catch (IOException legacyError) {
                legacyError.addSuppressed(utf8Error);
                throw legacyError;
            }
        }
    }
}
