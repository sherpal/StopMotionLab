create table movie (
    id               integer not null primary key autoincrement,
    name             text not null,
    created_at       integer not null,
    last_update_at   integer not null,
    soft_delete_at   integer           -- time at which it was "deleted", effectively removing it from UIs, but still recoverable
);

create table image (
    uuid         text not null primary key,
    content_type text,
    image        blob not null
);

create table movie_to_image (
    movie_id             integer not null,
    image_uuid           text not null,
    image_index_in_movie integer, -- Index of the image in the movie, null when it was temporarily removed
    foreign key (movie_id) references movie(id),
    foreign key (image_uuid) references image(uuid)
);
