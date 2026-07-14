path = "app/src/main/java/com/neroflix/tv/app/activities/DetailActivity.java"

with open(path, "r") as f:
    content = f.read()

old = '                    intent.putExtra("movie_poster",   getIntent().getStringExtra("movie_poster"));\n                    intent.putExtra("vote_average",   getIntent().getFloatExtra("vote_average", 0f));'

new = '                    String _poster = (movie != null && movie.getPosterPath() != null && !movie.getPosterPath().isEmpty())\n                        ? movie.getPosterPath() : getIntent().getStringExtra("movie_poster");\n                    intent.putExtra("movie_poster",   _poster);\n                    float _rating = (movie != null && movie.getVoteAverage() > 0)\n                        ? (float) movie.getVoteAverage()\n                        : (float) getIntent().getDoubleExtra("movie_rating", 0.0);\n                    intent.putExtra("vote_average",   _rating);'

if old in content:
    content = content.replace(old, new)
    with open(path, "w") as f:
        f.write(content)
    print("SUCCESS: Fixed poster and rating!")
else:
    print("ERROR: Pattern not found. Check file manually.")
