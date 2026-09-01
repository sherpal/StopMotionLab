
create table raw_event_envelope(
    offset           integer not null primary key autoincrement, -- global, gapless-per-insert log position, used by projections
    entity_id        integer not null,
    sequence_number  integer not null,
    event_payload    text not null,
    entity_kind      text not null,
    timestamp        integer not null
);

create unique index envelope_index on raw_event_envelope (entity_id, entity_kind, sequence_number);

-- Tracks how far each registered projection has read through raw_event_envelope, by offset.
create table projection_checkpoint (
    projection_name text not null primary key,
    last_offset     integer not null,
    updated_at      integer not null
);

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
