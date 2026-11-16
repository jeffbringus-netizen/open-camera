package net.sourceforge.opencamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores and resolves the active product-relative folder beneath Open Camera's
 * existing, persistently granted Storage Access Framework tree.
 */
public final class ProductFolderStore {
    private static final int MAX_RECENT_FOLDERS = 10;

    private ProductFolderStore() {
    }

    public static String getActivePath(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PreferenceKeys.ProductFolderPathPreferenceKey, "");
    }

    public static void setActivePath(Context context, String path) {
        String normalized = normalizePath(path);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().putString(PreferenceKeys.ProductFolderPathPreferenceKey, normalized).apply();
    }


    /** Returns the most recently used product folders, newest first. */
    public static ArrayList<String> getRecentPaths(Context context) {
        String stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PreferenceKeys.RecentProductFoldersPreferenceKey, "");
        ArrayList<String> paths = new ArrayList<>();
        if( stored == null || stored.isEmpty() )
            return paths;

        for( String candidate : stored.split("\\n") ) {
            try {
                String path = normalizePath(candidate);
                if( !path.isEmpty() && !paths.contains(path) )
                    paths.add(path);
            }
            catch(IllegalArgumentException ignored) {
                // Ignore invalid legacy entries rather than preventing the picker from opening.
            }
        }
        return paths;
    }

    /** Adds a selected product folder to the recent list, keeping the newest first. */
    public static void rememberRecentPath(Context context, String path) {
        String normalized = normalizePath(path);
        if( normalized.isEmpty() )
            return;

        ArrayList<String> paths = getRecentPaths(context);
        paths.remove(normalized);
        paths.add(0, normalized);
        while( paths.size() > MAX_RECENT_FOLDERS )
            paths.remove(paths.size() - 1);

        StringBuilder stored = new StringBuilder();
        for( String recent : paths ) {
            if( stored.length() > 0 )
                stored.append('\n');
            stored.append(recent);
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PreferenceKeys.RecentProductFoldersPreferenceKey, stored.toString())
                .apply();
    }

    /** Lists the immediate child directories at a relative path beneath the SAF root. */
    public static ArrayList<String> listChildFolders(Context context, Uri treeUri, String relativePath) throws IOException {
        if( treeUri == null )
            throw new IOException("No main storage folder has been selected");

        String normalized = normalizePath(relativePath);
        String rootDocumentId;
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        }
        catch(IllegalArgumentException e) {
            throw new IOException("Invalid main storage folder", e);
        }

        Uri currentFolder = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
        if( !normalized.isEmpty() ) {
            for( String segment : normalized.split("/") ) {
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, DocumentsContract.getDocumentId(currentFolder));
                String childDocumentId = null;
                Cursor cursor = null;
                try {
                    cursor = context.getContentResolver().query(
                            childrenUri,
                            new String[] {
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE
                            },
                            null, null, null);
                    if( cursor == null )
                        throw new IOException("Unable to list folders in the selected storage provider");

                    int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                    while( cursor.moveToNext() ) {
                        if( segment.equals(cursor.getString(nameIndex))
                                && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeIndex)) ) {
                            childDocumentId = cursor.getString(idIndex);
                            break;
                        }
                    }
                }
                finally {
                    if( cursor != null )
                        cursor.close();
                }

                if( childDocumentId == null )
                    throw new IOException("Folder no longer exists: " + segment);
                currentFolder = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId);
            }
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(currentFolder));
        ArrayList<String> folders = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    childrenUri,
                    new String[] {
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    },
                    null, null, null);
            if( cursor == null )
                throw new IOException("Unable to list folders in the selected storage provider");

            int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while( cursor.moveToNext() ) {
                if( DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeIndex)) ) {
                    String name = cursor.getString(nameIndex);
                    if( name != null && !name.isEmpty() )
                        folders.add(name);
                }
            }
        }
        finally {
            if( cursor != null )
                cursor.close();
        }
        Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
        return folders;
    }

    /** A directory or image row in the folder browser. */
    public static final class FolderEntry {
        public final String name;
        public final String mimeType;
        public final Uri uri;
        public final long lastModified;

        FolderEntry(String name, String mimeType, Uri uri, long lastModified) {
            this.name = name;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.uri = uri;
            this.lastModified = lastModified;
        }

        public boolean isDirectory() {
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
        }

        public boolean isImage() {
            if( mimeType.startsWith("image/") )
                return true;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                    || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".heic")
                    || lower.endsWith(".heif") || lower.endsWith(".dng") || lower.endsWith(".tif")
                    || lower.endsWith(".tiff") || lower.endsWith(".bmp");
        }
    }

    /** Resolves an existing folder without creating missing path components. */
    public static Uri findFolderUri(Context context, Uri treeUri, String relativePath) throws IOException {
        if( treeUri == null )
            throw new IOException("No main storage folder has been selected");
        final String normalized = normalizePath(relativePath);
        final String rootDocumentId;
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        }
        catch(IllegalArgumentException e) {
            throw new IOException("Invalid main storage folder", e);
        }
        Uri current = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
        if( normalized.isEmpty() )
            return current;

        for( String segment : normalized.split("/") ) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getDocumentId(current));
            String childId = null;
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(children,
                        new String[] {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                        }, null, null, null);
                if( cursor == null )
                    throw new IOException("Unable to list folders in the selected storage provider");
                int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while( cursor.moveToNext() ) {
                    if( segment.equals(cursor.getString(nameIndex))
                            && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeIndex)) ) {
                        childId = cursor.getString(idIndex);
                        break;
                    }
                }
            }
            finally {
                if( cursor != null )
                    cursor.close();
            }
            if( childId == null )
                throw new IOException("Folder no longer exists: " + segment);
            current = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
        }
        return current;
    }

    /** Lists child directories and images, with directories first and newest images next. */
    public static ArrayList<FolderEntry> listFolderContents(Context context, Uri treeUri, String relativePath) throws IOException {
        Uri folder = findFolderUri(context, treeUri, relativePath);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(folder));
        ArrayList<FolderEntry> folders = new ArrayList<>();
        ArrayList<FolderEntry> images = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(children,
                    new String[] {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    }, null, null, null);
            if( cursor == null )
                throw new IOException("Unable to list items in the selected folder");
            int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while( cursor.moveToNext() ) {
                String name = cursor.getString(nameIndex);
                String mime = cursor.getString(mimeIndex);
                String id = cursor.getString(idIndex);
                if( name == null || id == null )
                    continue;
                Uri document = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                long modified = modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)
                        ? cursor.getLong(modifiedIndex) : 0L;
                FolderEntry entry = new FolderEntry(name, mime, document, modified);
                if( entry.isDirectory() )
                    folders.add(entry);
                else if( entry.isImage() )
                    images.add(entry);
            }
        }
        finally {
            if( cursor != null )
                cursor.close();
        }
        Collections.sort(folders, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        Collections.sort(images, (a, b) -> {
            if( a.lastModified != b.lastModified )
                return Long.compare(b.lastModified, a.lastModified);
            return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
        });
        ArrayList<FolderEntry> result = new ArrayList<>(folders.size() + images.size());
        result.addAll(folders);
        result.addAll(images);
        return result;
    }

    /** Renames an existing SAF document, returning its provider-updated URI. */
    public static Uri renameDocument(Context context, Uri documentUri, String newName) throws IOException {
        try {
            Uri renamed = DocumentsContract.renameDocument(context.getContentResolver(), documentUri, newName);
            if( renamed == null )
                throw new IOException("The storage provider could not rename the item");
            return renamed;
        }
        catch(SecurityException | IllegalArgumentException | IllegalStateException e) {
            throw new IOException("Unable to rename item", e);
        }
    }

    /** Deletes an existing SAF document. Directory deletion removes the directory and its contents. */
    public static void deleteDocument(Context context, Uri documentUri) throws IOException {
        try {
            if( !DocumentsContract.deleteDocument(context.getContentResolver(), documentUri) )
                throw new IOException("The storage provider did not delete the item");
        }
        catch(SecurityException | IllegalArgumentException | IllegalStateException e) {
            throw new IOException("Unable to delete item", e);
        }
    }

    /** Keeps the active and recent product paths in sync after a folder rename. */
    public static void renamePathReferences(Context context, String oldPath, String newPath) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String active = preferences.getString(PreferenceKeys.ProductFolderPathPreferenceKey, "");
        String renamedActive = replacePathPrefix(active, oldPath, newPath);
        ArrayList<String> recents = getRecentPaths(context);
        ArrayList<String> renamed = new ArrayList<>();
        for( String recent : recents ) {
            String candidate = replacePathPrefix(recent, oldPath, newPath);
            if( !candidate.isEmpty() && !renamed.contains(candidate) )
                renamed.add(candidate);
        }
        preferences.edit().putString(PreferenceKeys.ProductFolderPathPreferenceKey, renamedActive)
                .putString(PreferenceKeys.RecentProductFoldersPreferenceKey, joinPaths(renamed)).apply();
    }

    /** Removes deleted paths and their descendants from active/recent product destinations. */
    public static void removePathReferences(Context context, String deletedPath) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String active = preferences.getString(PreferenceKeys.ProductFolderPathPreferenceKey, "");
        if( isSameOrChildPath(active, deletedPath) )
            active = "";
        ArrayList<String> retained = new ArrayList<>();
        for( String recent : getRecentPaths(context) ) {
            if( !isSameOrChildPath(recent, deletedPath) && !retained.contains(recent) )
                retained.add(recent);
        }
        preferences.edit().putString(PreferenceKeys.ProductFolderPathPreferenceKey, active)
                .putString(PreferenceKeys.RecentProductFoldersPreferenceKey, joinPaths(retained)).apply();
    }

    private static boolean isSameOrChildPath(String path, String parent) {
        return path != null && parent != null
                && (path.equals(parent) || path.startsWith(parent + "/"));
    }

    private static String replacePathPrefix(String path, String oldPath, String newPath) {
        if( path == null || oldPath == null || newPath == null || !isSameOrChildPath(path, oldPath) )
            return path == null ? "" : path;
        return newPath + path.substring(oldPath.length());
    }

    private static String joinPaths(List<String> paths) {
        StringBuilder stored = new StringBuilder();
        for( String path : paths ) {
            if( stored.length() > 0 )
                stored.append('\n');
            stored.append(path);
        }
        return stored.toString();
    }

    /**
     * Recursively lists folder paths beneath the selected SAF root. File rows are skipped.
     */
    public static ArrayList<String> listAllFolders(Context context, Uri treeUri) throws IOException {
        if( treeUri == null )
            throw new IOException("No main storage folder has been selected");

        final String rootDocumentId;
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        }
        catch(IllegalArgumentException e) {
            throw new IOException("Invalid main storage folder", e);
        }

        final class FolderEntry {
            final String documentId;
            final String path;
            FolderEntry(String documentId, String path) {
                this.documentId = documentId;
                this.path = path;
            }
        }

        ArrayDeque<FolderEntry> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        ArrayList<String> folders = new ArrayList<>();
        pending.add(new FolderEntry(rootDocumentId, ""));

        final String[] projection = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        while( !pending.isEmpty() ) {
            FolderEntry parent = pending.removeFirst();
            if( parent.documentId == null || !visited.add(parent.documentId) )
                continue;

            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId);
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(childrenUri, projection, null, null, null);
                if( cursor == null )
                    throw new IOException("Unable to list folders in the selected storage provider");

                int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while( cursor.moveToNext() ) {
                    if( !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeIndex)) )
                        continue;
                    String name = cursor.getString(nameIndex);
                    String documentId = cursor.getString(idIndex);
                    if( name == null || name.isEmpty() || documentId == null )
                        continue;

                    String path = parent.path.isEmpty() ? name : parent.path + "/" + name;
                    try {
                        path = normalizePath(path);
                    }
                    catch(IllegalArgumentException ignored) {
                        continue;
                    }

                    int depth = 1;
                    for( int i = 0; i < path.length(); i++ ) {
                        if( path.charAt(i) == '/' )
                            depth++;
                    }
                    if( depth > 32 )
                        continue;
                    folders.add(path);
                    if( depth < 32 )
                        pending.addLast(new FolderEntry(documentId, path));
                }
            }
            finally {
                if( cursor != null )
                    cursor.close();
            }
        }
        Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
        return folders;
    }

    /**
     * Validates a slash-separated, relative folder path. A blank path means the
     * selected SAF root itself. Dot segments and platform path separators are rejected.
     */
    public static String normalizePath(String path) {
        if( path == null || path.trim().isEmpty() )
            return "";

        String value = path.trim();
        if( value.startsWith("/") || value.endsWith("/") || value.contains("//") || value.indexOf('\\') >= 0 )
            throw new IllegalArgumentException("Folder path must be relative and use / separators");

        String[] segments = value.split("/", -1);
        StringBuilder normalized = new StringBuilder();
        for( String rawSegment : segments ) {
            String segment = rawSegment.trim();
            if( segment.isEmpty() || ".".equals(segment) || "..".equals(segment) )
                throw new IllegalArgumentException("Invalid folder name");
            for( int i = 0; i < segment.length(); i++ ) {
                if( Character.isISOControl(segment.charAt(i)) )
                    throw new IllegalArgumentException("Control characters are not allowed in folder names");
            }
            if( normalized.length() > 0 )
                normalized.append('/');
            normalized.append(segment);
        }
        return normalized.toString();
    }

    /**
     * Finds or creates each folder component under the selected SAF tree and
     * returns a tree-aware URI for the resolved directory.
     */
    public static Uri resolveFolderUri(Context context, Uri treeUri, String relativePath) throws IOException {
        if( treeUri == null )
            throw new IOException("No main storage folder has been selected");

        final String normalized = normalizePath(relativePath);
        final String rootDocumentId;
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        }
        catch(IllegalArgumentException e) {
            throw new IOException("Invalid main storage folder", e);
        }

        Uri currentFolder = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
        if( normalized.isEmpty() )
            return currentFolder;

        for( String segment : normalized.split("/")) {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getDocumentId(currentFolder));
            String childDocumentId = null;
            boolean conflictingFileName = false;

            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        childrenUri,
                        new String[] {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                        },
                        null, null, null);
                if( cursor == null )
                    throw new IOException("Unable to list folders in the selected storage provider");

                int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while( cursor.moveToNext() ) {
                    if( segment.equals(cursor.getString(nameIndex)) ) {
                        if( DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeIndex)) ) {
                            childDocumentId = cursor.getString(idIndex);
                        }
                        else {
                            conflictingFileName = true;
                        }
                        break;
                    }
                }
            }
            finally {
                if( cursor != null )
                    cursor.close();
            }

            if( childDocumentId != null ) {
                currentFolder = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId);
            }
            else {
                if( conflictingFileName )
                    throw new IOException("A file with this folder name already exists: " + segment);
                Uri created = DocumentsContract.createDocument(
                        context.getContentResolver(),
                        currentFolder,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        segment);
                if( created == null )
                    throw new IOException("Unable to create folder: " + segment);
                currentFolder = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getDocumentId(created));
            }
        }
        return currentFolder;
    }
}
