package com.filemanager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

public class FileShareProvider extends ContentProvider {
    public static final String AUTH="com.filemanager.fileprovider";

    public boolean onCreate(){ return true; }

    File fileOf(Uri u){
        String p=u.getPath();
        return p==null?null:new File(p);
    }

    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        File f=fileOf(uri);
        if(f==null || !f.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        File f=fileOf(uri);
        if(f==null || !f.isFile()) return null;
        if(projection==null) projection=new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
        MatrixCursor c=new MatrixCursor(projection);
        Object[] row=new Object[projection.length];
        for(int i=0;i<projection.length;i++){
            if(OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i]=f.getName();
            else if(OpenableColumns.SIZE.equals(projection[i])) row[i]=Long.valueOf(f.length());
        }
        c.addRow(row);
        return c;
    }

    public String getType(Uri uri){
        File f=fileOf(uri);
        if(f!=null){
            String n=f.getName();
            int dot=n.lastIndexOf('.');
            if(dot>=0){
                String m=MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(dot+1).toLowerCase(Locale.US));
                if(m!=null) return m;
            }
        }
        return "application/octet-stream";
    }

    public Uri insert(Uri uri,ContentValues values){ return null; }
    public int delete(Uri uri,String selection,String[] selectionArgs){ return 0; }
    public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){ return 0; }
}
