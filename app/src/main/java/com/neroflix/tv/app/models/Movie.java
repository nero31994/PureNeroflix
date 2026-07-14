package com.neroflix.tv.app.models;

public class Movie {
    private int id;
    private String title;
    private String overview;
    private String posterPath;
    private String backdropPath;
    private String releaseDate;
    private double voteAverage;
    private int voteCount;
    private String mediaType; // "movie" or "tv"
    private String genres;
    private String tagline;
    private int runtime;
    private String status;
    private String originalLanguage;

    public Movie() {}

    public Movie(int id, String title, String overview, String posterPath,
                 String backdropPath, String releaseDate, double voteAverage,
                 String mediaType) {
        this.id = id;
        this.title = title;
        this.overview = overview;
        this.posterPath = posterPath;
        this.backdropPath = backdropPath;
        this.releaseDate = releaseDate;
        this.voteAverage = voteAverage;
        this.mediaType = mediaType;
    }

    // Getters
    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getOverview() { return overview; }
    public String getPosterPath() { return posterPath; }
    public String getBackdropPath() { return backdropPath; }
    public String getReleaseDate() { return releaseDate; }
    public double getVoteAverage() { return voteAverage; }
    public int getVoteCount() { return voteCount; }
    public String getMediaType() { return mediaType; }
    public String getGenres() { return genres; }
    public String getTagline() { return tagline; }
    public int getRuntime() { return runtime; }
    public String getStatus() { return status; }
    public String getOriginalLanguage() { return originalLanguage; }

    // Setters
    public void setId(int id) { this.id = id; }
    public void setTitle(String title) { this.title = title; }
    public void setOverview(String overview) { this.overview = overview; }
    public void setPosterPath(String posterPath) { this.posterPath = posterPath; }
    public void setBackdropPath(String backdropPath) { this.backdropPath = backdropPath; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    public void setVoteAverage(double voteAverage) { this.voteAverage = voteAverage; }
    public void setVoteCount(int voteCount) { this.voteCount = voteCount; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public void setGenres(String genres) { this.genres = genres; }
    public void setTagline(String tagline) { this.tagline = tagline; }
    public void setRuntime(int runtime) { this.runtime = runtime; }
    public void setStatus(String status) { this.status = status; }
    public void setOriginalLanguage(String originalLanguage) { this.originalLanguage = originalLanguage; }

    public String getFullPosterUrl(String size) {
        if (posterPath == null || posterPath.isEmpty()) return null;
        return "https://image.tmdb.org/t/p/" + size + posterPath;
    }

    public String getFullBackdropUrl(String size) {
        if (backdropPath == null || backdropPath.isEmpty()) return null;
        return "https://image.tmdb.org/t/p/" + size + backdropPath;
    }

    public String getYear() {
        if (releaseDate != null && releaseDate.length() >= 4) {
            return releaseDate.substring(0, 4);
        }
        return "";
    }

    public String getRatingFormatted() {
        return String.format("%.1f", voteAverage);
    }

    public String getRuntimeFormatted() {
        if (runtime <= 0) return "";
        int hours = runtime / 60;
        int minutes = runtime % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
