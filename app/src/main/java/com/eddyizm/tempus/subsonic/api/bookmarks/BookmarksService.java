package com.eddyizm.tempus.subsonic.api.bookmarks;

import com.eddyizm.tempus.subsonic.base.ApiResponse;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

public interface BookmarksService {
    @GET("getPlayQueue")
    Call<ApiResponse> getPlayQueue(@QueryMap Map<String, String> params);

    @GET("savePlayQueue")
    Call<ApiResponse> savePlayQueue(@QueryMap Map<String, String> params, @Query("id") List<String> ids, @Query("current") String current, @Query("position") long position);

    /** The same call with the song ids in a form body: a long queue does not fit in a URL. */
    @FormUrlEncoded
    @POST("savePlayQueue")
    Call<ApiResponse> savePlayQueueForm(@QueryMap Map<String, String> params, @Field("id") List<String> ids, @Field("current") String current, @Field("position") long position);

    @GET("getBookmarks")
    Call<ApiResponse> getBookmarks(@QueryMap Map<String, String> params);

    @GET("createBookmark")
    Call<ApiResponse> createBookmark(@QueryMap Map<String, String> params, @Query("id") String id, @Query("position") long position);

    @GET("deleteBookmark")
    Call<ApiResponse> deleteBookmark(@QueryMap Map<String, String> params, @Query("id") String id);
}
