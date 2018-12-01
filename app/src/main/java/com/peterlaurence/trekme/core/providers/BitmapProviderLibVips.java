package com.peterlaurence.trekme.core.providers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.peterlaurence.trekme.core.map.Map;
import com.qozix.tileview.graphics.BitmapProvider;
import com.qozix.tileview.tiles.Tile;

import java.io.File;

/**
 * An implementation of {@link BitmapProvider}, able to read maps generated by LibVips.
 */
public class BitmapProviderLibVips implements BitmapProvider {
    private final File mDirectory;

    public static final String GENERATOR_NAME = "VIPS";

    private final String mImageExtension;

    private static final BitmapFactory.Options bitmapLoadingOptions = new BitmapFactory.Options();

    static {
        bitmapLoadingOptions.inPreferredConfig = Bitmap.Config.RGB_565;
    }

    public BitmapProviderLibVips(Map map){
        mDirectory = map.getDirectory();
        mImageExtension = map.getImageExtension();
    }

    @Override
    public Bitmap getBitmap(Tile tile, Context context) {
        Object zoomLvl = tile.getData();
        if(zoomLvl instanceof Integer){

            String relativePathString = zoomLvl.toString() + File.separator + tile.getRow() +
                    File.separator + tile.getColumn() + mImageExtension;
            try {
                File tileFile = new File(mDirectory, relativePathString);

                return BitmapFactory.decodeFile(tileFile.getPath(), bitmapLoadingOptions);
            } catch (OutOfMemoryError | Exception e){
                // this is probably an out of memory error - we can try sleeping (this method won't
                // be called in the UI thread) or try again (or give up)
            }
        }
        return null;
    }
}