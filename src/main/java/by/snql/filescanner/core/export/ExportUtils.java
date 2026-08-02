package by.snql.filescanner.core.export;

import by.snql.filescanner.core.util.SizeFormat;
import by.snql.filescanner.model.FileNode;
import com.google.gson.stream.JsonWriter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams a scanned tree to CSV/JSON/HTML. All formats write incrementally to
 * the destination file rather than building the whole document in memory, so
 * exporting a multi-million-node tree does not risk an OutOfMemoryError.
 */
public final class ExportUtils {

    private ExportUtils() {}

    public enum Format { CSV, JSON, HTML }

    public static void export(FileNode root, Path outputFile, Format format) throws IOException {
        try (var writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            switch (format) {
                case CSV -> writeCsv(root, writer);
                case JSON -> writeJson(root, outputFile);
                case HTML -> writeHtml(root, writer);
            }
        }
    }

    /** Convenience overload accepting a format name case-insensitively (csv/json/html). */
    public static void export(FileNode root, Path outputFile, String format) throws IOException {
        export(root, outputFile, Format.valueOf(format.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    private static void writeCsv(FileNode root, Writer w) throws IOException {
        w.write("Path,Name,Size,Directory\n");
        writeCsvRows(root, w);
    }

    private static void writeCsvRows(FileNode node, Writer w) throws IOException {
        w.write(csvField(node.getPath().toString()));
        w.write(',');
        w.write(csvField(node.getName()));
        w.write(',');
        w.write(Long.toString(node.getSize()));
        w.write(',');
        w.write(Boolean.toString(node.isDirectory()));
        w.write('\n');
        for (var child : node.getChildren()) {
            writeCsvRows(child, w);
        }
    }

    private static String csvField(String value) {
        // RFC 4198: double embedded quotes, always quote to be safe with commas/newlines.
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void writeJson(FileNode root, Path outputFile) throws IOException {
        try (var jw = new JsonWriter(Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8))) {
            jw.setIndent("  ");
            writeJsonNode(root, jw);
        }
    }

    private static void writeJsonNode(FileNode node, JsonWriter jw) throws IOException {
        jw.beginObject();
        jw.name("name").value(node.getName());
        jw.name("path").value(node.getPath().toString());
        jw.name("size").value(node.getSize());
        jw.name("directory").value(node.isDirectory());
        if (!node.getChildren().isEmpty()) {
            jw.name("children").beginArray();
            for (var child : node.getChildren()) {
                writeJsonNode(child, jw);
            }
            jw.endArray();
        }
        jw.endObject();
    }

    private static void writeHtml(FileNode root, Writer w) throws IOException {
        w.write("""
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"><title>File Scanner Report</title>
                <style>body{font-family:sans-serif;margin:20px}
                h1{color:#3498db}table{border-collapse:collapse;width:100%}
                th,td{border:1px solid #ddd;padding:6px;text-align:left}
                th{background:#3498db;color:#fff}tr:nth-child(even){background:#f2f2f2}
                .dir{font-weight:bold}</style></head><body>
                <h1>Disk Usage Report</h1>
                <table><tr><th>Path</th><th>Name</th><th>Size</th><th>Type</th></tr>
                """);
        writeHtmlRows(root, w);
        w.write("</table></body></html>\n");
    }

    private static void writeHtmlRows(FileNode node, Writer w) throws IOException {
        String rowClass = node.isDirectory() ? " class=\"dir\"" : "";
        w.write("<tr"); w.write(rowClass); w.write("><td>");
        w.write(escapeHtml(node.getPath().toString()));
        w.write("</td><td>");
        w.write(escapeHtml(node.getName()));
        w.write("</td><td>");
        w.write(SizeFormat.format(node.getSize()));
        w.write("</td><td>");
        w.write(node.isDirectory() ? "Directory" : "File");
        w.write("</td></tr>\n");
        for (var child : node.getChildren()) {
            writeHtmlRows(child, w);
        }
    }

    private static String escapeHtml(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
