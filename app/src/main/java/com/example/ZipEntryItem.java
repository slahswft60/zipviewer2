package com.example;

import android.webkit.MimeTypeMap;
import java.util.Locale;

public class ZipEntryItem {
    private final String path;
    private final String name;
    private final long size;
    private final long compressedSize;
    private final boolean isDirectory;

    public ZipEntryItem(String path, long size, long compressedSize, boolean isDirectory) {
        this.path = path != null ? path : "";
        this.size = size;
        this.compressedSize = compressedSize;
        this.isDirectory = isDirectory;

        // Calculate simple display name from path
        String cleanPath = this.path;
        if (cleanPath.endsWith("/")) {
            cleanPath = cleanPath.substring(0, cleanPath.length() - 1);
        }
        int lastSlash = cleanPath.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < cleanPath.length() - 1) {
            this.name = cleanPath.substring(lastSlash + 1);
        } else {
            this.name = cleanPath.isEmpty() ? "/" : cleanPath;
        }
    }

    public String getPath() {
        return path;
    }

    public String getSimpleName() {
        return name;
    }

    public long getSize() {
        return size;
    }

    public long getCompressedSize() {
        return compressedSize;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public String getFormattedSize() {
        if (isDirectory) {
            return "Folder";
        }
        if (size <= 0) {
            return "0 B";
        }
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        if (digitGroups >= units.length) {
            digitGroups = units.length - 1;
        }
        return String.format(Locale.US, "%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public String getSuggestedMimeType() {
        if (isDirectory) {
            return null;
        }
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < name.length() - 1) {
            String ext = name.substring(dotIndex + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "*/*";
    }

    public int getIconType() {
        if (isDirectory) {
            return 1; // Folder
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg")) {
            return 2; // Image
        } else if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")
                || lower.endsWith(".tar") || lower.endsWith(".gz")) {
            return 3; // Archive
        }
        return 0; // Generic file
    }
}
