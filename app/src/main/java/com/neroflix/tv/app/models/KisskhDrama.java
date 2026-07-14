package com.neroflix.tv.app.models;

/**
 * Represents a drama/movie from kisskh API.
 * Used by KisskhActivity for browse and search.
 */
public class KisskhDrama {
    private int    id;
    private String title;
    private String poster;       // full URL from kisskh
    private String type;         // "Korean Drama", "Movie", etc.
    private String status;       // "Ongoing", "Completed"
    private String description;
    private float  rating;
    private int    episodeCount;

    public KisskhDrama() {}

    public int    getId()           { return id; }
    public String getTitle()        { return title; }
    public String getPoster()       { return poster; }
    public String getType()         { return type; }
    public String getStatus()       { return status; }
    public String getDescription()  { return description; }
    public float  getRating()       { return rating; }
    public int    getEpisodeCount() { return episodeCount; }

    public void setId(int id)                   { this.id = id; }
    public void setTitle(String title)           { this.title = title; }
    public void setPoster(String poster)         { this.poster = poster; }
    public void setType(String type)             { this.type = type; }
    public void setStatus(String status)         { this.status = status; }
    public void setDescription(String d)         { this.description = d; }
    public void setRating(float rating)          { this.rating = rating; }
    public void setEpisodeCount(int count)       { this.episodeCount = count; }
}
