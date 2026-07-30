package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public final class ExportUtils {

    private ExportUtils() {}

    public static void export(FileNode root, Path outputFile, String format) throws IOException {
        var content = switch (format.toLowerCase()) {
            case "csv" -> toCsv(root);
            case "json" -> toJson(root);
            case "html" -> toHtml(root);
            default -> throw new IllegalArgumentException("Unsupported format: " + format);
        };
        Files.writeString(outputFile, content);
    }

    private static String toCsv(FileNode root) {
        var sb = new StringBuilder("Path,Name,Size,Directory\n");
        appendCsvRows(root, sb);
        return sb.toString();
    }

    private static void appendCsvRows(FileNode node, StringBuilder sb) {
        sb.append('"').append(node.getPath()).append("\",");
        sb.append('"').append(node.getName()).append("\",");
        sb.append(node.getSize()).append(",");
        sb.append(node.isDirectory()).append("\n");
        for (var child : node.getChildren()) {
            appendCsvRows(child, sb);
        }
    }

    private static String toJson(FileNode root) {
        return nodeToJson(root);
    }

    private static String nodeToJson(FileNode node) {
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\":\"").append(escapeJson(node.getName())).append("\",");
        sb.append("\"path\":\"").append(escapeJson(node.getPath().toString())).append("\",");
        sb.append("\"size\":").append(node.getSize()).append(",");
        sb.append("\"directory\":").append(node.isDirectory());
        if (!node.getChildren().isEmpty()) {
            sb.append(",\"children\":[");
            var children = node.getChildren().stream()
                    .map(ExportUtils::nodeToJson)
                    .collect(Collectors.joining(","));
            sb.append(children);
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String toHtml(FileNode root) {
        return """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"><title>File Scanner Report</title>
                <style>body{font-family:sans-serif;margin:20px}
                h1{color:#3498db}table{border-collapse:collapse;width:100%%}
                th,td{border:1px solid #ddd;padding:6px;text-align:left}
                th{background:#3498db;color:#fff}tr:nth-child(even){background:#f2f2f2}
                .dir{font-weight:bold}</style></head><body>
                <h1>Disk Usage Report</h1>
                <table><tr><th>Path</th><th>Name</th><th>Size</th><th>Type</th></tr>
                %s
                </table></body></html>
                """.formatted(toHtmlRows(root));
    }

    private static String toHtmlRows(FileNode node) {
        var sb = new StringBuilder();
        String type = node.isDirectory() ? "class='dir'" : "";
        String size = MainWindow.formatSize(node.getSize());
        sb.append("<tr ").append(type).append("><td>").append(node.getPath())
                .append("</td><td>").append(node.getName())
                .append("</td><td>").append(size).append("</td><td>")
                .append(node.isDirectory() ? "Directory" : "File")
                .append("</td></tr>\n");
        for (var child : node.getChildren()) {
            sb.append(toHtmlRows(child));
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
