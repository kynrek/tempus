package com.eddyizm.tempus.util;

import com.eddyizm.tempus.interfaces.StarCallback;
import com.eddyizm.tempus.repository.FavoriteRepository;
import com.eddyizm.tempus.subsonic.models.AlbumID3;

import java.util.Date;

/**
 * Stars or unstars an album, as the album page does: on the server now, or once back online if
 * it can't be reached. Used by the star on each release of an artist's page.
 */
public final class AlbumFavorite {
    private static final FavoriteRepository favorites = new FavoriteRepository();

    private AlbumFavorite() {
    }

    public static boolean isFavorite(AlbumID3 album) {
        return FavoriteRegistry.resolve(FavoriteRegistry.Kind.ALBUM, album.getId(), album.getStarred() != null);
    }

    /** Flips the album's favourite; returns whether it is a favourite now. */
    public static boolean toggle(AlbumID3 album) {
        boolean star = !isFavorite(album);
        String id = album.getId();
        if (NetworkUtil.isOffline()) {
            favorites.starLater(null, id, null, star);
        } else if (star) {
            favorites.star(null, id, null, new StarCallback() {
                @Override
                public void onError() {
                    favorites.starLater(null, id, null, true);
                }
            });
        } else {
            favorites.unstar(null, id, null, new StarCallback() {
                @Override
                public void onError() {
                    favorites.starLater(null, id, null, false);
                }
            });
        }
        album.setStarred(star ? new Date() : null);
        return star;
    }
}
