package be.doeraene.services.filestorage

import java.nio.file.Path

class FileStorageService(directory: Path) {

  def writeFile(path: Path, bytes: Array[Byte]): Unit = os.write(root / os.RelPath(path), bytes)

  def readFile(path: Path): Array[Byte] = os.read.bytes(root / os.RelPath(path))

  def writeFileContents(path: Path, content: String): Unit = os.write(root / os.RelPath(path), content)

  def readFileContents(path: Path): String = os.read(root / os.RelPath(path))

  def streamFile(path: Path): geny.Readable = os.read.stream(root / os.RelPath(path))

  private val root = os.Path(directory)

  if !os.exists(root) then os.makeDir(root)

}

object FileStorageService {
  extension (path: Path) {
    def /(segment: String): Path = path.resolve(segment)
  }
}
