package net.sourceforge.opencamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

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
                stored.append('\\n');
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
