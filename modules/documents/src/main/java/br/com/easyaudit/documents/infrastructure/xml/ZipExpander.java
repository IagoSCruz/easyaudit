package br.com.easyaudit.documents.infrastructure.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Expande um ZIP em memória, sem extrair para disco (sem *zip slip*: os nomes só servem para exibição). Limites:
 * 20 MB de ZIP, 500 entradas, 1 MB por entrada descompactada e 100 MB descompactados no total (proteção contra
 * *zip bomb*). Diretórios e a sujeira de metadados (por exemplo {@code __MACOSX/}) são ignorados.
 */
public final class ZipExpander {

    public static final int MAX_ARCHIVE_BYTES = 20 * 1024 * 1024;
    public static final int MAX_ENTRIES = 500;
    public static final int MAX_ENTRY_BYTES = 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    /** Uma entrada do ZIP; {@code content} é nulo quando a entrada excede {@link #MAX_ENTRY_BYTES}. */
    public record Entry(String name, byte[] content) {
        public boolean tooLarge() {
            return content == null;
        }
    }

    /** O ZIP inteiro é inválido ou excede algum limite. */
    public static class ArchiveRejectedException extends RuntimeException {
        public ArchiveRejectedException(String message) {
            super(message);
        }
    }

    private ZipExpander() {
    }

    public static boolean looksLikeZip(byte[] content) {
        return content.length > 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3 && content[3] == 4;
    }

    public static List<Entry> expand(byte[] archive) {
        if (archive.length > MAX_ARCHIVE_BYTES) {
            throw new ArchiveRejectedException("O ZIP excede " + MAX_ARCHIVE_BYTES / (1024 * 1024) + " MB.");
        }
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || ignored(entry.getName())) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new ArchiveRejectedException("O ZIP tem mais de " + MAX_ENTRIES + " entradas.");
                }
                Read read = readLimited(zip, MAX_TOTAL_BYTES - total);
                if (read == null) {
                    throw new ArchiveRejectedException("O ZIP excede " + MAX_TOTAL_BYTES / (1024 * 1024)
                            + " MB descompactados (possível zip bomb).");
                }
                total += read.size();
                entries.add(new Entry(entry.getName(), read.size() > MAX_ENTRY_BYTES ? null : read.bytes()));
            }
        } catch (ZipException e) {
            throw new ArchiveRejectedException("ZIP inválido: " + e.getMessage());
        } catch (IOException e) {
            throw new ArchiveRejectedException("Não foi possível ler o ZIP: " + e.getMessage());
        }
        return entries;
    }

    /** Conteúdo lido (só guardado se couber em {@link #MAX_ENTRY_BYTES}) e tamanho descompactado real. */
    private record Read(byte[] bytes, long size) {
    }

    /** Lê a entrada atual; devolve nulo se ela passar do orçamento total restante (aborta sem descompactar o resto). */
    private static Read readLimited(InputStream in, long remainingBudget) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            read += n;
            if (read > remainingBudget) {
                return null;
            }
            // entradas acima de 1 MB continuam sendo contadas, mas só os primeiros bytes ficam em memória
            if (out.size() <= MAX_ENTRY_BYTES) {
                out.write(buffer, 0, n);
            }
        }
        return new Read(out.toByteArray(), read);
    }

    private static boolean ignored(String name) {
        String base = name.substring(name.lastIndexOf('/') + 1);
        return name.startsWith("__MACOSX/") || base.startsWith(".");
    }
}
