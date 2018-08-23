package uk.gov.hmrc.customs.notification.services

import akka.stream.scaladsl.Source
import com.google.inject.Singleton
import play.api.libs.json.{JsObject, JsValue, Json}
import reactivemongo.play.json.JsObjectDocumentWriter
import reactivemongo.play.json.collection.JSONCollection
// ReactiveMongo extensions
import reactivemongo.akkastream.{AkkaStreamCursor, State, cursorProducer}
import uk.gov.hmrc.customs.notification.domain.ClientNotification

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class Streamer {

  private implicit val system = akka.actor.ActorSystem("reactivemongo-akkastream")
  private implicit val materializer = akka.stream.ActorMaterializer.create(system)

  def stream(collection: JSONCollection, selector: JsObject = Json.obj()): (Source[ClientNotification, Future[State]]) = {
    val cursor: AkkaStreamCursor[ClientNotification] =
      collection.find(selector)./*sort(Json.obj("id" -> 1)).*/cursor[ClientNotification]()

    val src: Source[ClientNotification, Future[State]] = cursor.documentSource()

    src
  }
}
