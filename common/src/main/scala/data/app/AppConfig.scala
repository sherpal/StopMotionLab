package data.app

case class AppConfig(isProd: Boolean, port: Int) {
  def scheme: String = if isProd then "https" else "http"
}
