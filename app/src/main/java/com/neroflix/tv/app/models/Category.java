package com.neroflix.tv.app.models;

import java.util.ArrayList;
import java.util.List;

public class Category {
    private String title;
    private String endpoint; // TMDB endpoint for this category
    private List<Movie> movies;
    private String mediaType; // "movie" or "tv"

    public Category(String title, String endpoint, String mediaType) {
        this.title = title;
        this.endpoint = endpoint;
        this.mediaType = mediaType;
        this.movies = new ArrayList<>();
    }

    public String getTitle() { return title; }
    public String getEndpoint() { return endpoint; }
    public List<Movie> getMovies() { return movies; }
    public String getMediaType() { return mediaType; }

    public void setMovies(List<Movie> movies) { this.movies = movies; }
    public void setTitle(String title) { this.title = title; }
}
