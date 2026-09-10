package data.movie

import io.circe.Codec

/** A movie that has been deleted, kept around just long enough to offer restoring it. Unlike [[MovieMetadata]] there
  * is no `lastUpdateAt`: the deleted-movie side table only ever stores the id and name it had at the moment of
  * deletion (see `DeletedMovie` in the server's database tables).
  */
case class DeletedMovieMetadata(id: Movie.Id, name: String) derives Codec
